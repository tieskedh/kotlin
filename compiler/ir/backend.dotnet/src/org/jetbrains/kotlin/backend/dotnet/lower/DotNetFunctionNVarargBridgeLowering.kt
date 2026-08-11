/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetClassifierInfoCache
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Lowers Common function types with 23 or more arguments to the runtime-owned FunctionN view.
 *
 * The logical Function23+ classifier and its full signature remain in KLIB. Physically, the same
 * object implements one non-generic FunctionN capability whose Invoke accepts an object array and
 * whose arity getter distinguishes otherwise identical runtime carriers. Calls retain Kotlin's
 * receiver/argument evaluation order, and every bridge validates its argument count independently
 * so foreign CLR callers observe the same boundary as reflective Kotlin calls.
 */
internal class DotNetFunctionNVarargBridgeLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass, IrElementTransformerVoidWithContext() {
    private val classifierInfoCache = DotNetClassifierInfoCache()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        val function = expression.symbol.owner as? IrSimpleFunction
            ?: return super.visitFunctionAccess(expression)
        if (function.name != OperatorNameConventions.INVOKE) {
            return super.visitFunctionAccess(expression)
        }
        val arity = logicalBigArityOfInvoke(function)
            ?: return super.visitFunctionAccess(expression)
        if (expression.nonDispatchArguments.size != arity) return super.visitFunctionAccess(expression)
        val receiver = expression.dispatchReceiver
            ?: error("Internal .NET backend error: Function$arity.invoke has no dispatch receiver")
        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression)
        return builder.irBlock(resultType = expression.type) {
            val receiverTemporary = irTemporary(receiver.transformVoid(), nameHint = "<functionNReceiver>")
            val arrayType = this@DotNetFunctionNVarargBridgeLowering.context.irBuiltIns.arrayClass
                .typeWithArguments(listOf(this@DotNetFunctionNVarargBridgeLowering.context.irBuiltIns.anyNType))
            val argumentsTemporary = irTemporary(
                irCall(this@DotNetFunctionNVarargBridgeLowering.context.irBuiltIns.arrayOfNulls, arrayType).apply {
                    typeArguments[0] = this@DotNetFunctionNVarargBridgeLowering.context.irBuiltIns.anyNType
                    arguments[0] = irInt(arity)
                    type = arrayType
                },
                nameHint = "<functionNArguments>",
            )
            expression.nonDispatchArguments.forEachIndexed { index, argument ->
                +irCall(arraySet.symbol).apply {
                    arguments[0] = irGet(argumentsTemporary)
                    arguments[1] = irInt(index)
                    arguments[2] = argument!!.transformVoid()
                }
            }
            val invocation = irCall(this@DotNetFunctionNVarargBridgeLowering.context.bigArityCallableSymbols.invoke).apply {
                arguments[0] = irImplicitCast(
                    irGet(receiverTemporary),
                    this@DotNetFunctionNVarargBridgeLowering.context.bigArityCallableSymbols.irClass.defaultType,
                )
                arguments[1] = irGet(argumentsTemporary)
            }
            +irImplicitCast(invocation, expression.type)
        }
    }

    override fun visitClassNew(declaration: IrClass): IrStatement {
        val logicalArities = sequence<IrType> {
            yieldAll(declaration.superTypes)
            yieldAll(getAllSubstitutedSupertypes(declaration))
        }.mapNotNull { type -> logicalBigArity(type.classOrNull?.owner) }
            .distinct()
            .toList()
        if (logicalArities.isEmpty()) return super.visitClassNew(declaration)

        declaration.transformChildrenVoid(this)
        if (logicalArities.size != 1) {
            dotNetUnsupported(
                "one class implementing multiple FunctionN execution arities (${logicalArities.joinToString()})"
            )
        }
        val arity = logicalArities.single()
        declaration.superTypes = declaration.superTypes.filterNot { superType ->
            val owner = superType.classOrNull?.owner ?: return@filterNot false
            classifierInfoCache[owner].bigFunctionArity != null
        }
        if (declaration.superTypes.none { it.classOrNull == context.bigArityCallableSymbols.irClass.symbol }) {
            declaration.superTypes += context.bigArityCallableSymbols.irClass.defaultType
        }
        // The Framework profile cannot carry a default bridge body on an interface. Its physical
        // FunctionN supertype publishes the abstract capability; each concrete implementor below
        // receives the body that adapts object[] to the typed Kotlin invoke declaration.
        if (declaration.isInterface) return declaration

        val invoke = declaration.functions.singleOrNull { function ->
            function.name == OperatorNameConventions.INVOKE &&
                    function.parameters.count { it.kind == IrParameterKind.Regular } == arity &&
                    (function.overriddenSymbols.asSequence().map { it.owner } + function.allOverridden())
                        .any { overridden -> logicalBigArity(overridden.parentClassOrNull) == arity }
        } ?: if (declaration.modality == Modality.ABSTRACT || declaration.modality == Modality.SEALED) {
            return declaration
        } else {
            error(
                "Internal .NET backend error: ${declaration.name} implements Function$arity without one typed invoke"
            )
        }
        invoke.overriddenSymbols = invoke.overriddenSymbols.filterNot { overridden ->
            logicalBigArity(overridden.owner.parentClassOrNull) != null
        }
        declaration.addFunctionNBridge(invoke, arity)
        declaration.addFunctionNArityGetter(arity)
        return declaration
    }

    private fun IrClass.addFunctionNBridge(invoke: IrSimpleFunction, arity: Int) {
        val superInvoke = context.bigArityCallableSymbols.invoke
        addFunction {
            startOffset = invoke.startOffset
            endOffset = invoke.endOffset
            origin = IrDeclarationOrigin.BRIDGE
            name = superInvoke.name
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply bridge@{
            overriddenSymbols = listOf(superInvoke.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += superInvoke.parameters.single { it.kind == IrParameterKind.Regular }.copyTo(this)
            val arrayParameter = parameters.single { it.kind == IrParameterKind.Regular }
            body = context.createIrBuilder(symbol).irBlockBody {
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irNotEquals(
                        irCall(arraySizeGetter.symbol).apply {
                            arguments[0] = irGet(arrayParameter)
                        },
                        irInt(arity),
                    ),
                    irCall(context.irBuiltIns.illegalArgumentExceptionSymbol).apply {
                        arguments[0] = irString("Expected $arity arguments")
                    },
                )
                val typedCall = irCall(invoke).apply {
                    arguments[0] = irGet(this@bridge.dispatchReceiverParameter!!)
                    invoke.parameters.filter { it.kind == IrParameterKind.Regular }
                        .forEachIndexed { index, parameter ->
                            val erasedArgument = irCall(arrayGet.symbol).apply {
                                arguments[0] = irGet(arrayParameter)
                                arguments[1] = irInt(index)
                            }
                            arguments[parameter.indexInParameters] = irImplicitCast(erasedArgument, parameter.type)
                        }
                }
                +irReturn(irImplicitCast(typedCall, context.irBuiltIns.anyNType))
            }
        }
    }

    private fun IrClass.addFunctionNArityGetter(arity: Int) {
        val superGetter = context.bigArityCallableSymbols.arityGetter
        val property = addProperty {
            origin = IrDeclarationOrigin.BRIDGE
            name = Name.identifier("arity")
            visibility = DescriptorVisibilities.PUBLIC
        }
        property.addGetter {
            origin = IrDeclarationOrigin.BRIDGE
            returnType = context.irBuiltIns.intType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
        }.apply {
            overriddenSymbols = listOf(superGetter.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irInt(arity))
            }
        }
    }

    private fun logicalBigArity(irClass: IrClass?): Int? {
        irClass ?: return null
        val info = classifierInfoCache[irClass]
        info.bigFunctionArity?.let { return it }
        info.bigKFunctionArity?.let { return it }

        // Common continuation lowering leaves the logical SuspendFunctionN invoke token in
        // calls while adding one ordinary execution argument. JVM's FunctionN pass admits the
        // call from that argument count; retain the more explicit arity check here while making
        // the same N + Continuation boundary visible to this target's FunctionN model.
        if (!irClass.symbol.isSuspendFunction() && !irClass.symbol.isKSuspendFunction()) return null
        return irClass.name.asString()
            .removePrefix("K")
            .removePrefix("SuspendFunction")
            .toIntOrNull()
            ?.plus(1)
            ?.takeIf { it >= BuiltInFunctionArity.BIG_ARITY }
    }

    /**
     * A call may retain the fake override declared on an intermediate functional interface rather
     * than the FunctionN declaration itself. Follow that function's override graph, as the JVM
     * functional-type predicate does, while retaining .NET's exact logical-arity requirement.
     */
    private fun logicalBigArityOfInvoke(function: IrSimpleFunction): Int? {
        val arities = (sequenceOf(function) + function.allOverridden())
            .mapNotNull { overridden -> logicalBigArity(overridden.parentClassOrNull) }
            .distinct()
            .toList()
        if (arities.size > 1) {
            dotNetUnsupported(
                "one invoke member inheriting multiple FunctionN execution arities (${arities.joinToString()})"
            )
        }
        return arities.singleOrNull()
    }

    private fun IrExpression.transformVoid(): IrExpression =
        transform(this@DotNetFunctionNVarargBridgeLowering, null)

    private val arraySet by lazy {
        context.irBuiltIns.arrayClass.owner.functions.single { function ->
            function.name == OperatorNameConventions.SET
        }
    }

    private val arrayGet by lazy {
        context.irBuiltIns.arrayClass.owner.functions.single { function ->
            function.name == OperatorNameConventions.GET
        }
    }

    private val arraySizeGetter by lazy {
        context.irBuiltIns.arrayClass.owner.properties.single { property ->
            property.name.asString() == "size"
        }.getter ?: error("Internal .NET backend error: Array.size has no getter")
    }
}
