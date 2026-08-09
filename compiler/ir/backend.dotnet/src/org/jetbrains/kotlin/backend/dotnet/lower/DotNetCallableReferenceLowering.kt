/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.AbstractFunctionReferenceLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetFunctionReferenceFlags
import org.jetbrains.kotlin.backend.dotnet.dotNetCallableDeclarationFlags
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericArray
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.backend.dotnet.dotNetFixedFunctionArityOrNull
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
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
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasShape
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
import java.util.IdentityHashMap

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
    private val executionInvokeByReference = IdentityHashMap<IrRichFunctionReference, IrSimpleFunction>()

    private val IrRichFunctionReference.isStateMachineSuspendLambda: Boolean
        get() = origin.isLambda && invokeFunction.isSuspend

    override fun getReferenceClassName(reference: IrRichFunctionReference): Name =
        Name.identifier(if (reference.origin.isLambda) "lambda" else "functionReference")

    override fun getSuperClassType(reference: IrRichFunctionReference): IrType =
        if (reference.isStateMachineSuspendLambda) context.symbols.coroutineImpl.owner.defaultType
        else if (reference.hasFunctionReferenceIdentity) context.functionReferenceSymbols.baseClass.defaultType
        else context.irBuiltIns.anyType

    override fun IrBuilderWithScope.generateSuperClassConstructorCall(
        constructor: IrConstructor,
        superClassType: IrType,
        functionReference: IrRichFunctionReference,
    ): IrDelegatingConstructorCall {
        if (functionReference.isStateMachineSuspendLambda) {
            val superConstructor = superClassType.classOrFail.owner.primaryConstructor
                ?: error("Internal .NET backend error: coroutine base has no primary constructor")
            val completion = constructor.parameters.single { it.origin == IrDeclarationOrigin.CONTINUATION }
            return irDelegatingConstructorCall(superConstructor).apply {
                arguments[0] = irGet(completion)
            }
        }
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
            val hasSignatureSurface =
                (functionReference.type.isKFunction() || functionReference.type.isKSuspendFunction()) &&
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

    override fun getExtraConstructorParameters(
        constructor: IrConstructor,
        reference: IrRichFunctionReference,
    ): List<IrValueParameter> {
        if (!reference.isStateMachineSuspendLambda) return emptyList()
        val completion = context.symbols.coroutineImpl.owner.primaryConstructor!!.parameters.single()
        return listOf(
            buildValueParameter(constructor) {
                name = completion.name
                type = completion.type
                origin = IrDeclarationOrigin.CONTINUATION
                kind = IrParameterKind.Regular
            }
        )
    }

    override fun IrBuilderWithScope.getExtraConstructorArgument(
        parameter: IrValueParameter,
        reference: IrRichFunctionReference,
    ): IrExpression? = if (reference.isStateMachineSuspendLambda) irNull(parameter.type) else null

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
        val exactType = context.exactCallableSymbols.typeFor(executionType)
        if (exactType == null) {
            executionInvokeByReference[functionReference] = invokeFunction
            return
        }
        val arity = (executionType as IrSimpleType).arguments.size - 1
        val exactInvoke = splitExactInvokeFromErasedBridge(invokeFunction, arity)
        executionInvokeByReference[functionReference] = exactInvoke
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
        if (!reference.type.isKFunction() && !reference.type.isKSuspendFunction()) return
        if (reference.reflectionTargetSymbol == null) return
        // JVM's callSuspend appends the current continuation and then delegates to KCallable.call.
        // Keep that positional contract: the runtime arity includes the continuation-shaped
        // FunctionN slot. Named suspend invocation needs a distinct callSuspendBy operation to
        // supply the continuation outside the KParameter map, so plain callBy fails closed until
        // that separate reflection surface is selected.
        addPositionalCall(functionReferenceClass, reference)
        if (reference.type.isKSuspendFunction()) {
            addUnsupportedSuspendNamedCall(functionReferenceClass, reference)
        } else {
            addNamedCall(functionReferenceClass, reference)
        }
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

    /** Implements KCallable.callBy while leaving producer-mask construction to the shared lowering. */
    private fun addNamedCall(functionReferenceClass: IrClass, reference: IrRichFunctionReference) {
        val superCallBy = context.irBuiltIns.kCallableClass.owner.functions
            .singleOrNull { function -> function.name.asString() == "callBy" }
            ?: return
        val erasedCallBy = context.functionReferenceSymbols.callByErased ?: return
        val argumentParameter = superCallBy.parameters.single { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        functionReferenceClass.addFunction {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = superCallBy.name
            visibility = superCallBy.visibility
            modality = Modality.FINAL
            returnType = reference.invokeFunction.returnType
        }.apply callBy@{
            overriddenSymbols = listOf(superCallBy.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += argumentParameter.copyTo(this)
            body = context.createIrBuilder(symbol).irBlockBody {
                val erasedCall = irCall(erasedCallBy).apply {
                    arguments[0] = irGet(this@callBy.dispatchReceiverParameter!!)
                    arguments[1] = irGet(this@callBy.parameters.single { it.kind == IrParameterKind.Regular })
                }
                +irReturn(irImplicitCast(erasedCall, this@callBy.returnType))
            }
        }

        val target = reference.reflectionTargetSymbol?.owner
            ?: error("Internal .NET backend error: KFunction callBy target is absent")
        val exposedParameters = target.exposedCallableParameters(reference.boundValues.size)
        val executionInvoke = executionInvokeByReference[reference]
            ?: error("Internal .NET backend error: KFunction callBy has no execution method")
        val executionParameters = executionInvoke.parameters.filter { it.kind == IrParameterKind.Regular }
        check(executionParameters.size == exposedParameters.size) {
            "Internal .NET backend error: reflected callable exposes ${exposedParameters.size} parameters " +
                    "but executes with ${executionParameters.size}"
        }

        val optionalIndices = exposedParameters.indices.filter { index ->
            exposedParameters[index].hasDotNetKotlinOptionalSemantics()
        }
        if (optionalIndices.isNotEmpty()) {
            addDefaultCallCapability(
                functionReferenceClass,
                reference,
                target,
                exposedParameters,
                executionInvoke,
                optionalIndices,
            )
        }
        val varargIndices = exposedParameters.indices.filter { index ->
            exposedParameters[index].varargElementType != null
        }
        if (varargIndices.isNotEmpty()) {
            addEmptyVarargCapability(
                functionReferenceClass,
                reference,
                exposedParameters,
                varargIndices,
            )
        }
    }

    /** Implements the mandatory KCallable slot without pretending a map contains Continuation. */
    private fun addUnsupportedSuspendNamedCall(
        functionReferenceClass: IrClass,
        reference: IrRichFunctionReference,
    ) {
        val superCallBy = context.irBuiltIns.kCallableClass.owner.functions
            .singleOrNull { function -> function.name.asString() == "callBy" }
            ?: return
        val argumentParameter = superCallBy.parameters.single { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        functionReferenceClass.addFunction {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = superCallBy.name
            visibility = superCallBy.visibility
            modality = Modality.FINAL
            returnType = reference.invokeFunction.returnType
        }.apply {
            overriddenSymbols = listOf(superCallBy.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += argumentParameter.copyTo(this)
            body = context.createIrBuilder(symbol).irBlockBody {
                +irCall(this@DotNetCallableReferenceLowering.context.symbols.throwUnsupportedOperationException).apply {
                    arguments[0] = irString(
                        "callBy cannot supply a suspend continuation; use a coroutine-aware reflective call."
                    )
                }
            }
        }
    }

    private fun addDefaultCallCapability(
        functionReferenceClass: IrClass,
        reference: IrRichFunctionReference,
        target: IrFunction,
        exposedParameters: List<IrValueParameter>,
        executionInvoke: IrSimpleFunction,
        optionalIndices: List<Int>,
    ) {
        val base = context.functionReferenceSymbols.callDefaultErased
        val baseParameters = base.parameters.filter { it.kind == IrParameterKind.Regular }
        val helpers = buildList {
            val optionalMask = optionalIndices.fold(0) { mask, index -> mask or (1 shl index) }
            for (mask in 1 until (1 shl exposedParameters.size)) {
                if (mask and optionalMask != mask) continue
                add(mask to addDefaultInvocationHelper(
                    functionReferenceClass,
                    reference,
                    target,
                    exposedParameters,
                    executionInvoke,
                    mask,
                    baseParameters[0],
                ))
            }
        }
        functionReferenceClass.addFunction {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = base.name
            visibility = base.visibility
            modality = Modality.FINAL
            returnType = base.returnType
        }.apply capability@{
            overriddenSymbols = listOf(base.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += baseParameters.map { parameter -> parameter.copyTo(this) }
            val args = parameters.filter { it.kind == IrParameterKind.Regular }[0]
            val mask = parameters.filter { it.kind == IrParameterKind.Regular }[1]
            body = context.createIrBuilder(symbol).irBlockBody {
                val branches = helpers.map { entry ->
                    val value = entry.first
                    val helper = entry.second
                    irBranch(
                        irEquals(irGet(mask), irInt(value)),
                        irCall(helper).apply {
                            arguments[0] = irGet(this@capability.dispatchReceiverParameter!!)
                            arguments[1] = irGet(args)
                        },
                    )
                } + irElseBranch(
                    irCall(context.irBuiltIns.illegalArgumentExceptionSymbol).apply {
                        arguments[0] = irString("Invalid reflective default-argument mask.")
                    }
                )
                +irReturn(irWhen(context.irBuiltIns.anyNType, branches))
            }
        }
    }

    private fun addDefaultInvocationHelper(
        functionReferenceClass: IrClass,
        reference: IrRichFunctionReference,
        target: IrFunction,
        exposedParameters: List<IrValueParameter>,
        executionInvoke: IrSimpleFunction,
        mask: Int,
        baseArgumentsParameter: IrValueParameter,
    ): IrSimpleFunction {
        val helper = functionReferenceClass.addFunction {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = Name.identifier("CallDefaultMask$mask")
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += baseArgumentsParameter.copyTo(this)
        }
        val arguments = helper.parameters.single { it.kind == IrParameterKind.Regular }
        val executionParameters = executionInvoke.parameters.filter { it.kind == IrParameterKind.Regular }
        val executionParameterIndex = executionParameters.withIndex().associate { it.value.symbol to it.index }
        val omittedTargetParameters = exposedParameters.indices
            .filter { index -> mask and (1 shl index) != 0 }
            .mapTo(linkedSetOf()) { index -> exposedParameters[index].symbol }
        val body = executionInvoke.body?.deepCopyWithSymbols(helper)
            ?: error("Internal .NET backend error: KFunction callBy execution method has no body")
        var matchingCalls = 0
        val transformer = object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                executionInvoke.dispatchReceiverParameter?.let { receiver ->
                    if (expression.symbol == receiver.symbol) {
                        return context.createIrBuilder(helper.symbol).at(expression)
                            .irGet(helper.dispatchReceiverParameter!!)
                    }
                }
                val index = executionParameterIndex[expression.symbol] ?: return expression
                val builder = context.createIrBuilder(helper.symbol).at(expression)
                val arrayGet = context.irBuiltIns.arrayClass.owner.functions.single { function ->
                    function.name.asString() == "get"
                }
                return builder.irCall(arrayGet.symbol, expression.type).apply {
                    this.arguments[0] = builder.irGet(arguments)
                    this.arguments[1] = builder.irInt(index)
                }
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
                val transformed = super.visitFunctionAccess(expression) as IrFunctionAccessExpression
                if (transformed.symbol != target.symbol) return transformed
                matchingCalls++
                transformed.symbol.owner.parameters.forEachIndexed { index, parameter ->
                    if (parameter.symbol in omittedTargetParameters) transformed.arguments[index] = null
                }
                return transformed
            }

            override fun visitReturn(expression: IrReturn): IrExpression {
                val transformed = super.visitReturn(expression) as IrReturn
                if (transformed.returnTargetSymbol != executionInvoke.symbol) return transformed
                transformed.returnTargetSymbol = helper.symbol
                val builder = context.createIrBuilder(helper.symbol).at(transformed)
                transformed.value = builder.irImplicitCast(transformed.value, context.irBuiltIns.anyNType)
                return transformed
            }
        }
        body.transformChildrenVoid(transformer)
        check(matchingCalls == 1) {
            "Internal .NET backend error: reflective default call for '${target.name}' found " +
                    "$matchingCalls target calls instead of one"
        }
        helper.body = body
        return helper
    }

    private fun addEmptyVarargCapability(
        functionReferenceClass: IrClass,
        reference: IrRichFunctionReference,
        exposedParameters: List<IrValueParameter>,
        varargIndices: List<Int>,
    ) {
        val base = context.functionReferenceSymbols.emptyVarargAt
        val baseIndex = base.parameters.single { it.kind == IrParameterKind.Regular }
        functionReferenceClass.addFunction {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = base.name
            visibility = base.visibility
            modality = Modality.FINAL
            returnType = base.returnType
        }.apply capability@{
            overriddenSymbols = listOf(base.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += baseIndex.copyTo(this)
            val indexParameter = parameters.single { it.kind == IrParameterKind.Regular }
            body = context.createIrBuilder(symbol).irBlockBody {
                val branches = varargIndices.map { index ->
                    val emptyArray = buildEmptyArray(exposedParameters[index].type)
                    irBranch(
                        irEquals(irGet(indexParameter), irInt(index)),
                        irImplicitCast(emptyArray, context.irBuiltIns.anyNType),
                    )
                } + irElseBranch(
                    irCall(context.irBuiltIns.illegalArgumentExceptionSymbol).apply {
                        arguments[0] = irString("Invalid reflective vararg position.")
                    }
                )
                +irReturn(irWhen(context.irBuiltIns.anyNType, branches))
            }
        }
    }

    private fun IrBuilderWithScope.buildEmptyArray(arrayType: IrType): IrExpression {
        if (arrayType.isDotNetGenericArray()) {
            val elementType = ((arrayType as IrSimpleType).arguments.single() as IrTypeProjection).type
            val invariantArrayType = context.irBuiltIns.arrayClass.owner
                .symbol.typeWithArguments(listOf(elementType))
            return irCall(context.irBuiltIns.arrayOfNulls, invariantArrayType).apply {
                typeArguments[0] = elementType
                arguments[0] = irInt(0)
                type = invariantArrayType
            }
        }
        val constructor = arrayType.classOrFail.owner.constructors.single { candidate ->
            candidate.hasShape(
                regularParameters = 1,
                parameterTypes = listOf(context.irBuiltIns.intType),
            )
        }
        return irCall(constructor.symbol).apply {
            arguments[0] = irInt(0)
            type = arrayType
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
        // The Common builder adds inherited fake declarations after every concrete capability
        // above has been generated. They are not executable members and are not needed by the
        // CLR: explicit invoke/bridge methods own their interface slots, while the remaining
        // members are inherited from the selected base class. Dropping the entire synthetic set
        // also prevents a later physical lowering from traversing expect/actual placeholder
        // symbols copied from KFunctionN's override graph.
        functionReferenceClass.declarations.removeAll { declaration ->
            declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE
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
            // This class is intentionally incomplete at callable-lowering time: the following
            // coroutine phases replace its suspend invoke body with an ordinary-IR state machine
            // and add the continuation-shaped FunctionN capability. Do not apply the old
            // suspend-ABI reservation or pre-state-machine fixed-arity check to that selected
            // lambda shape. Named coroutine-aware reflective invocation and export remain
            // separate surfaces; direct references use the sibling KFunction/FunctionN+1 model.
            functionReference.isStateMachineSuspendLambda -> null
            functionReference.invokeFunction.isSuspend -> null
            functionReferenceClass.superTypes.none { superType ->
                superType.classOrNull?.owner?.dotNetFixedFunctionArityOrNull() != null
            } ->
                "does not use a supported fixed Function0, Function1, Function2, or Function3 interface"
            else -> null
        }
    }
}

private fun IrFunction.exposedCallableParameters(boundReceiverCount: Int): List<IrValueParameter> {
    val receivers = parameters.filter { parameter -> parameter.kind != IrParameterKind.Regular }
    require(boundReceiverCount <= receivers.size) {
        "Internal .NET backend error: callable '$name' captures $boundReceiverCount receivers " +
                "but declares only ${receivers.size}"
    }
    return receivers.drop(boundReceiverCount) + parameters.filter { parameter ->
        parameter.kind == IrParameterKind.Regular
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

private fun IrRichFunctionReference.referenceFlags(): Int {
    val reflectionTarget = reflectionTargetSymbol?.owner
    val target = reflectionTarget as? IrSimpleFunction
    return listOfNotNull(
        (1 shl 0).takeIf { invokeFunction.isSuspend },
        (1 shl 1).takeIf { hasVarargConversion },
        (1 shl 2).takeIf { hasSuspendConversion },
        (1 shl 3).takeIf { hasUnitConversion },
        (1 shl 4).takeIf { isFunInterfaceConstructorAdapter() },
        DotNetFunctionReferenceFlags.IS_INLINE.takeIf { target?.isInline == true },
        DotNetFunctionReferenceFlags.IS_EXTERNAL.takeIf { target?.isExternal == true },
        DotNetFunctionReferenceFlags.IS_OPERATOR.takeIf { target?.isOperator == true },
        DotNetFunctionReferenceFlags.IS_INFIX.takeIf { target?.isInfix == true },
        DotNetFunctionReferenceFlags.IS_SUSPEND.takeIf { target?.isSuspend == true },
        reflectionTarget?.dotNetCallableDeclarationFlags(),
    ).sum()
}

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
