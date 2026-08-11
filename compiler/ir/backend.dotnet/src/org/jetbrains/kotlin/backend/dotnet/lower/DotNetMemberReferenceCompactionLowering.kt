/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.functionWithContinuations
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetKClassRuntime
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.invokeFun
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal data class DotNetMemberDispatcherEntry(
    val executionFunction: IrSimpleFunction,
    val defaultFunction: IrSimpleFunction?,
)

internal var IrClass.dotNetMemberDispatcherEntries: List<DotNetMemberDispatcherEntry>?
    by irAttribute(copyByDefault = false)

internal var IrClass.dotNetMemberDispatcherInvoke: IrSimpleFunction?
    by irAttribute(copyByDefault = false)

/**
 * Folds the ordinary reference classes emitted for one opted-in KClass factory into one local
 * dispatcher TypeDef. Runtime owns the fixed arity carriers; this producer retains only direct
 * ordinary-IR thunks, so defaults, suspend conversion and later representation lowerings remain
 * shared with every other Kotlin call.
 */
internal class DotNetMemberReferenceCompactionLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.name.asString() == DotNetKClassRuntime.MEMBER_FACTORY_HOLDER_NAME) {
                    declaration.declarations.filterIsInstance<IrSimpleFunction>()
                        .singleOrNull { function ->
                            function.name.asString() == DotNetKClassRuntime.MEMBER_FACTORY_METHOD_NAME
                        }
                        ?.let(::compactFactory)
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }

    private fun compactFactory(factory: IrSimpleFunction) {
        val body = factory.body as? IrBlockBody
            ?: error("Internal .NET backend error: reflected member factory has no block body")
        val candidates = mutableListOf<MemberCandidate>()
        body.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitBlock(expression: IrBlock) {
                val candidateClass = expression.statements.firstOrNull() as? IrClass
                val info = candidateClass?.dotNetReflectedMemberCallableInfo
                val constructorCall = expression.statements.lastOrNull() as? IrConstructorCall
                if (candidateClass != null && info != null &&
                    constructorCall?.symbol?.owner?.parent == candidateClass
                ) {
                    candidates += MemberCandidate(candidateClass, info)
                    return
                }
                expression.acceptChildrenVoid(this)
            }
        })
        if (candidates.isEmpty()) return

        val dispatcher = createDispatcher(factory, candidates)
        val originalStatements = body.statements.toList()
        lateinit var dispatcherVariable: IrVariable
        factory.body = context.createIrBuilder(factory.symbol).irBlockBody {
            +dispatcher
            dispatcherVariable = irTemporary(
                irCallConstructor(dispatcher.primaryConstructor!!.symbol, emptyList()),
                nameHint = "memberDispatcher",
            )
            originalStatements.forEach { statement -> +statement }
        }

        val candidateByClass = candidates.withIndex().associate { indexed ->
            indexed.value.generatedClass to IndexedCandidate(indexed.index, indexed.value)
        }
        factory.body!!.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitBlock(expression: IrBlock): IrExpression {
                val generatedClass = expression.statements.firstOrNull() as? IrClass
                val indexed = generatedClass?.let(candidateByClass::get)
                    ?: return super.visitBlock(expression)
                val baseArguments = functionReferenceBaseArguments(generatedClass)
                val builder = context.createIrBuilder(factory.symbol).at(expression)
                val factoryCall = builder.irCall(context.memberReferenceSymbols.createFunction).apply {
                    arguments[0] = builder.irGet(dispatcherVariable)
                    arguments[1] = builder.irInt(indexed.index)
                    arguments[2] = baseArguments[0]
                    arguments[3] = baseArguments[1]
                    arguments[4] = baseArguments[2]
                    check((baseArguments[3] as? IrConst)?.value == 0) {
                        "Internal .NET backend error: reflected member carrier unexpectedly captures values"
                    }
                    arguments[5] = baseArguments[4]
                    arguments[6] = baseArguments[5]
                    arguments[7] = baseArguments[6]
                    arguments[8] = baseArguments[7]
                    arguments[9] = builder.emptyVarargValues(indexed.candidate.info.exposedParameters)
                }
                val referenceType = generatedClass.superTypes.getOrNull(1)
                    ?: error("Internal .NET backend error: reflected member callable has no execution interface")
                return builder.irImplicitCast(factoryCall, referenceType)
            }
        })
    }

    private fun createDispatcher(
        factory: IrSimpleFunction,
        candidates: List<MemberCandidate>,
    ): IrClass {
        val anyArray = context.irBuiltIns.arrayClass.typeWithArguments(listOf(context.irBuiltIns.anyNType))
        val dispatcherType = context.irBuiltIns.functionN(3).symbol.typeWithArguments(
            listOf(
                context.irBuiltIns.intType,
                anyArray,
                context.irBuiltIns.intArray.owner.defaultType.makeNullable(),
                context.irBuiltIns.anyNType,
            )
        )
        val dispatcher = context.irFactory.buildClass {
            startOffset = factory.startOffset
            endOffset = factory.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = Name.special(DotNetKClassRuntime.MEMBER_DISPATCHER_CLASS_NAME)
            visibility = DescriptorVisibilities.LOCAL
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = factory
            superTypes = listOf(context.irBuiltIns.anyType, dispatcherType)
            createThisReceiverParameter()
        }
        val firstCallableName = candidates.first().generatedClass.dotNetInventedLocalClassName
            ?: error("Internal .NET backend error: reflected member callable has no invented CLR name")
        val factoryName = generateSequence(firstCallableName) { current ->
            current.substringBeforeLast('$', missingDelimiterValue = current)
                .takeIf { parent -> parent != current }
        }.first { current -> current.substringAfterLast('$').toIntOrNull() == null }
        dispatcher.dotNetInventedLocalClassName =
            "$factoryName\$${DotNetKClassRuntime.MEMBER_DISPATCHER_CLASS_NAME}"
        dispatcher.addConstructor {
            startOffset = factory.startOffset
            endOffset = factory.endOffset
            origin = IrDeclarationOrigin.DEFINED
            visibility = DescriptorVisibilities.PUBLIC
            isPrimary = true
        }.apply {
            body = context.createIrBuilder(symbol).irBlockBody {
                +irDelegatingConstructorCall(
                    this@DotNetMemberReferenceCompactionLowering.context.irBuiltIns.anyClass.owner
                        .constructors.single { constructor ->
                            constructor.parameters.none { parameter -> parameter.kind == IrParameterKind.Regular }
                        }
                )
                +IrInstanceInitializerCallImpl(
                    startOffset,
                    endOffset,
                    dispatcher.symbol,
                    this@DotNetMemberReferenceCompactionLowering.context.irBuiltIns.unitType,
                )
            }
        }

        val dispatcherEntries = candidates.mapIndexed { index, candidate ->
            DotNetMemberDispatcherEntry(
                executionFunction = moveThunk(
                    candidate.info.executionFunction,
                    dispatcher,
                    Name.special("<InvokeMember-$index>"),
                ),
                defaultFunction = candidate.info.defaultFunction?.let { function ->
                    moveThunk(function, dispatcher, Name.special("<InvokeDefault-$index>"))
                },
            )
        }
        val interfaceInvoke = context.irBuiltIns.functionN(3).invokeFun
            ?: error("Internal .NET backend error: Function3 has no invoke member")
        val dispatcherInvoke = dispatcher.addFunction {
            startOffset = factory.startOffset
            endOffset = factory.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = interfaceInvoke.name
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
            isOperator = true
        }.apply {
            overriddenSymbols = listOf(interfaceInvoke.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            addValueParameter("memberIndex", context.irBuiltIns.intType)
            addValueParameter("args", anyArray)
            addValueParameter("masks", context.irBuiltIns.intArray.owner.defaultType.makeNullable())
            body = context.createIrBuilder(symbol).irBlockBody {
                +irCall(
                    this@DotNetMemberReferenceCompactionLowering.context.symbols.throwUnsupportedOperationException
                ).apply {
                    arguments[0] = irString("Member dispatcher body has not been completed.")
                }
            }
        }
        dispatcher.dotNetMemberDispatcherEntries = dispatcherEntries
        dispatcher.dotNetMemberDispatcherInvoke = dispatcherInvoke
        return dispatcher
    }

    private fun moveThunk(
        function: IrSimpleFunction,
        dispatcher: IrClass,
        name: Name,
    ): IrSimpleFunction {
        (function.parent as? IrClass)?.declarations?.remove(function)
        function.parent = dispatcher
        function.name = name
        function.visibility = DescriptorVisibilities.PRIVATE
        function.modality = Modality.FINAL
        function.isOperator = false
        function.overriddenSymbols = emptyList()
        function.dispatchReceiverParameter?.type = dispatcher.defaultType
        dispatcher.declarations += function
        return function
    }

    private fun functionReferenceBaseArguments(generatedClass: IrClass): List<IrExpression> {
        val constructor = generatedClass.primaryConstructor
            ?: error("Internal .NET backend error: reflected member callable has no constructor")
        val delegatingCall = (constructor.body as? IrBlockBody)?.statements
            ?.filterIsInstance<IrDelegatingConstructorCall>()
            ?.singleOrNull { call -> call.symbol == context.functionReferenceSymbols.constructor.symbol }
            ?: error("Internal .NET backend error: reflected member callable does not initialize FunctionReferenceBase")
        return delegatingCall.arguments.map { argument ->
            argument ?: error("Internal .NET backend error: reflected member metadata argument is absent")
        }
    }

    private fun IrBuilderWithScope.emptyVarargValues(
        exposedParameters: List<IrValueParameter>,
    ): IrExpression {
        val varargParameters = exposedParameters.withIndex().filter { indexed ->
            indexed.value.varargElementType != null
        }
        val anyArray = context.irBuiltIns.arrayClass.typeWithArguments(listOf(context.irBuiltIns.anyNType))
        if (varargParameters.isEmpty()) return irNull(anyArray.makeNullable())
        return irBlock(resultType = anyArray) {
            val values = irTemporary(
                irCall(context.irBuiltIns.arrayOfNulls, anyArray).apply {
                    typeArguments[0] = context.irBuiltIns.anyNType
                    arguments[0] = irInt(exposedParameters.size)
                    type = anyArray
                },
                nameHint = "emptyVarargs",
            )
            val arraySet = context.irBuiltIns.arrayClass.owner.functions.single { function ->
                function.name.asString() == "set"
            }
            varargParameters.forEach { indexed ->
                +irCall(arraySet.symbol).apply {
                    arguments[0] = irGet(values)
                    arguments[1] = irInt(indexed.index)
                    arguments[2] = irImplicitCast(buildEmptyArray(indexed.value.type), context.irBuiltIns.anyNType)
                }
            }
            +irGet(values)
        }
    }

    private fun IrBuilderWithScope.buildEmptyArray(arrayType: IrType): IrExpression {
        if (arrayType is IrSimpleType && arrayType.classifier == context.irBuiltIns.arrayClass) {
            val elementType = (arrayType.arguments.single() as IrTypeProjection).type
            val invariantArrayType = context.irBuiltIns.arrayClass.typeWithArguments(listOf(elementType))
            return irCall(context.irBuiltIns.arrayOfNulls, invariantArrayType).apply {
                typeArguments[0] = elementType
                arguments[0] = irInt(0)
                type = invariantArrayType
            }
        }
        val constructor = arrayType.classOrFail.owner.constructors.single { candidate ->
            candidate.parameters.singleOrNull { parameter -> parameter.kind == IrParameterKind.Regular }
                ?.type == context.irBuiltIns.intType
        }
        return irCall(constructor.symbol).apply {
            arguments[0] = irInt(0)
            type = arrayType
        }
    }

    private data class MemberCandidate(
        val generatedClass: IrClass,
        val info: DotNetReflectedMemberCallableInfo,
    )

    private data class IndexedCandidate(
        val index: Int,
        val candidate: MemberCandidate,
    )
}

/** Completes direct dispatcher calls only after suspend/default helpers have their physical ABI. */
internal class DotNetMemberDispatcherBodyLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                val entries = declaration.dotNetMemberDispatcherEntries
                val invoke = declaration.dotNetMemberDispatcherInvoke
                if (entries != null && invoke != null) populateBody(invoke, entries)
                declaration.acceptChildrenVoid(this)
            }
        })
    }

    private fun populateBody(
        invoke: IrSimpleFunction,
        entries: List<DotNetMemberDispatcherEntry>,
    ) {
        val regular = invoke.parameters.filter { parameter -> parameter.kind == IrParameterKind.Regular }
        val memberIndex = regular[0]
        val args = regular[1]
        val masks = regular[2]
        invoke.body = context.createIrBuilder(invoke.symbol).irBlockBody {
            val branches = entries.mapIndexed { index, entry ->
                val executionFunction = entry.executionFunction.functionWithContinuations
                    ?: entry.executionFunction
                val directCall = callExecution(invoke, executionFunction, args)
                val result = entry.defaultFunction?.let { defaultFunction ->
                    irIfThenElse(
                        context.irBuiltIns.anyNType,
                        irEquals(irGet(masks), irNull(masks.type)),
                        directCall,
                        irImplicitCast(
                            irCall(defaultFunction).apply {
                                arguments[0] = irGet(invoke.dispatchReceiverParameter!!)
                                arguments[1] = irGet(args)
                                arguments[2] = irImplicitCast(
                                    irGet(masks),
                                    context.irBuiltIns.intArray.owner.defaultType,
                                )
                            },
                            context.irBuiltIns.anyNType,
                        ),
                    )
                } ?: directCall
                irBranch(irEquals(irGet(memberIndex), irInt(index)), result)
            } + irElseBranch(
                irCall(context.irBuiltIns.illegalArgumentExceptionSymbol).apply {
                    arguments[0] = irString("Invalid reflected member dispatcher index.")
                }
            )
            +irReturn(irWhen(context.irBuiltIns.anyNType, branches))
        }
    }

    private fun IrBuilderWithScope.callExecution(
        invoke: IrSimpleFunction,
        execution: IrSimpleFunction,
        args: IrValueParameter,
    ): IrExpression {
        val arrayGet = context.irBuiltIns.arrayClass.owner.functions.single { function ->
            function.name.asString() == "get"
        }
        val call = irCall(execution).apply {
            arguments[0] = irGet(invoke.dispatchReceiverParameter!!)
            execution.parameters
                .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                .forEachIndexed { index, parameter ->
                    arguments[parameter.indexInParameters] = irCall(arrayGet.symbol, parameter.type).apply {
                        arguments[0] = irGet(args)
                        arguments[1] = irInt(index)
                    }
                }
        }
        return irImplicitCast(call, context.irBuiltIns.anyNType)
    }
}
