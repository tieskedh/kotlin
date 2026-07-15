/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.irBlock as irBlockFromExpression
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericArray
import org.jetbrains.kotlin.backend.dotnet.isSupportedDotNetPrimitiveArray
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBuilder
import org.jetbrains.kotlin.ir.builders.irBlock as irBuilderBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrVarargElement
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.hasDefaultValue
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Replaces concrete Kotlin vararg arguments with ordinary array expressions before IL emission.
 * The declaration ABI is the corresponding CLR vector; the source-only `out` projection on a
 * reference vararg parameter is normalized to the invariant array representation already used by
 * the .NET backend.
 *
 * This follows the JVM/Native/Wasm lowering contract: omitted varargs become empty vectors,
 * argument and spread expressions are evaluated once in source order, and every expanded call
 * receives a fresh array. Spread copies use the existing array `size`/`get`/`set` surface rather
 * than adding a second codegen-only copying path. Open element types deliberately remain
 * untouched: `vararg T` keeps its projected `Array<out T>` ABI on the unsupported path until that
 * ABI is decided.
 */
internal class DotNetVarargLowering(
    context: DotNetBackendContext,
) : FileLoweringPass, IrBuildingTransformer(context) {
    private val irBuiltIns = context.irBuiltIns
    private val intPlus = irBuiltIns.intClass.owner.functions.single { function ->
        function.name == OperatorNameConventions.PLUS &&
                function.parameters.singleOrNull { it.kind == IrParameterKind.Regular }?.type?.isInt() == true
    }
    private val intLess = irBuiltIns.lessFunByOperandType.getValue(irBuiltIns.intClass)
    private val normalizedVarargTypes = mutableMapOf<IrValueSymbol, IrType>()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        declaration.parameters.forEach { parameter ->
            parameter.concreteVarargArrayTypeOrNull()?.let { arrayType ->
                parameter.type = arrayType
                normalizedVarargTypes[parameter.symbol] = arrayType
            }
        }
        return super.visitFunction(declaration)
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val transformed = super.visitVariable(declaration) as IrVariable
        val initializer = transformed.initializer as? IrGetValue ?: return transformed
        val sourceType = normalizedVarargTypes[initializer.symbol] ?: return transformed
        val invariantType = transformed.type.concreteProjectedArrayTypeOrNull() ?: return transformed
        if (sourceType == invariantType) {
            transformed.type = invariantType
            normalizedVarargTypes[transformed.symbol] = invariantType
        }
        return transformed
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        normalizedVarargTypes[expression.symbol]?.let { arrayType ->
            expression.type = arrayType
        }
        return expression
    }

    // Annotation arrays have a separate metadata representation and are not executable varargs.
    override fun visitAnnotation(expression: IrAnnotation): IrExpression = expression

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        val callee = expression.symbol.owner
        val builtinVararg = expression.arguments.singleOrNull() as? IrVararg
        if (callee.isOptimizedDotNetArrayOf() && expression.arguments.size == 1 &&
            expression.arguments[0] == null
        ) {
            // The literal intrinsic already treats an absent builtin vararg as an empty vector.
            return expression
        }
        if (callee.isOptimizedDotNetArrayOf() && builtinVararg != null &&
            builtinVararg.elements.none { it is IrSpreadElement }
        ) {
            // Preserve the established direct array-literal intrinsic. Its elements still need
            // ordinary recursive lowering, but visiting the IrVararg root would replace it.
            builtinVararg.elements.indices.forEach { index ->
                val element = builtinVararg.elements[index] as IrExpression
                builtinVararg.elements[index] = element.transform(this, null)
            }
            return expression
        }

        for (index in expression.arguments.indices) {
            if (expression.arguments[index] != null) continue
            val parameter = callee.parameters[index]
            val elementType = parameter.varargElementType ?: continue
            if (parameter.hasDefaultValue()) continue
            expression.arguments[index] = IrVarargImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                parameter.concreteVarargArrayTypeOrNull() ?: parameter.type,
                elementType,
            )
        }
        expression.transformChildrenVoid(this)
        return expression
    }

    override fun visitVararg(expression: IrVararg): IrExpression {
        expression.transformChildrenVoid(this)
        val arrayType = expression.concreteVarargArrayTypeOrNull() ?: return expression
        builder.at(expression)
        return if (expression.elements.any { it is IrSpreadElement }) {
            lowerVarargWithSpread(expression, arrayType)
        } else {
            lowerSimpleVararg(expression, arrayType)
        }
    }

    private fun lowerSimpleVararg(expression: IrVararg, arrayType: IrType): IrExpression =
        builder.irBlockFromExpression(expression, resultType = arrayType) {
            val result = irTemporary(
                createArray(arrayType, expression.varargElementType, irInt(expression.elements.size)),
                nameHint = "<vararg>",
            )
            expression.elements.forEachIndexed { index, element ->
                storeElement(
                    result,
                    irInt(index),
                    element as IrExpression,
                )
            }
            +irGet(result)
        }

    private fun lowerVarargWithSpread(expression: IrVararg, arrayType: IrType): IrExpression =
        builder.irBlockFromExpression(expression, resultType = arrayType) {
            val evaluated = expression.elements.map { element ->
                val spread = element as? IrSpreadElement
                EvaluatedElement(
                    element,
                    irTemporary(
                        spread?.expression ?: element as IrExpression,
                        nameHint = "<varargElement>",
                        irType = if (spread == null) expression.varargElementType else spread.expression.type,
                    ),
                )
            }
            val spreadSizes = evaluated.associateWith { element ->
                if (element.source is IrSpreadElement) {
                    irTemporary(arraySize(element.value), nameHint = "<spreadSize>")
                } else {
                    null
                }
            }
            val totalSize = evaluated.fold(irInt(0) as IrExpression) { size, element ->
                intPlus(
                    size,
                    spreadSizes.getValue(element)?.let { spreadSize -> irGet(spreadSize) } ?: irInt(1),
                )
            }
            val result = irTemporary(
                createArray(arrayType, expression.varargElementType, totalSize),
                nameHint = "<vararg>",
            )
            val destinationIndex = irTemporary(irInt(0), nameHint = "<varargIndex>", isMutable = true)

            for (element in evaluated) {
                val spreadSize = spreadSizes.getValue(element)
                if (spreadSize == null) {
                    storeElement(result, irGet(destinationIndex), irGet(element.value))
                    increment(destinationIndex)
                    continue
                }

                val sourceIndex = irTemporary(irInt(0), nameHint = "<spreadIndex>", isMutable = true)
                +irWhile().apply {
                    condition = irCall(intLess).apply {
                        arguments[0] = irGet(sourceIndex)
                        arguments[1] = irGet(spreadSize)
                    }
                    body = irBuilderBlock(
                        expression.startOffset,
                        expression.endOffset,
                        resultType = irBuiltIns.unitType,
                    ) {
                        storeElement(
                            result,
                            irGet(destinationIndex),
                            loadElement(element.value, sourceIndex),
                        )
                        increment(destinationIndex)
                        increment(sourceIndex)
                    }
                }
            }
            +irGet(result)
        }

    private fun IrBlockBuilder.createArray(
        arrayType: IrType,
        elementType: IrType,
        size: IrExpression,
    ): IrExpression {
        if (arrayType.isDotNetGenericArray()) {
            return irCall(irBuiltIns.arrayOfNulls, arrayType).apply {
                typeArguments[0] = elementType
                arguments[0] = size
                type = arrayType
            }
        }
        val constructor = arrayType.classOrNull!!.owner.constructors.single { candidate ->
            candidate.hasShape(
                regularParameters = 1,
                parameterTypes = listOf(irBuiltIns.intType),
            )
        }
        return irCall(constructor.symbol).apply {
            arguments[0] = size
            type = arrayType
        }
    }

    private fun IrBlockBuilder.arraySize(array: IrVariable): IrExpression =
        irCall(array.type.classOrNull!!.owner.getPropertyGetter("size")!!).apply {
            arguments[0] = irGet(array)
        }

    private fun IrBlockBuilder.loadElement(array: IrVariable, index: IrVariable): IrExpression =
        irCall(array.type.classOrNull!!.owner.functions.single { it.name == OperatorNameConventions.GET }.symbol).apply {
            arguments[0] = irGet(array)
            arguments[1] = irGet(index)
        }

    private fun IrBlockBuilder.storeElement(
        array: IrVariable,
        index: IrExpression,
        value: IrExpression,
    ) {
        +irCall(array.type.classOrNull!!.owner.functions.single { it.name == OperatorNameConventions.SET }.symbol).apply {
            arguments[0] = irGet(array)
            arguments[1] = index
            arguments[2] = value
        }
    }

    private fun IrBlockBuilder.increment(variable: IrVariable) {
        +irSet(variable.symbol, intPlus(irGet(variable), irInt(1)))
    }

    private fun IrBlockBuilder.intPlus(left: IrExpression, right: IrExpression): IrExpression =
        irCall(intPlus).apply {
            arguments[0] = left
            arguments[1] = right
        }

    private fun IrValueParameter.concreteVarargArrayTypeOrNull(): IrType? =
        varargElementType?.let { elementType -> type.concreteVarargArrayTypeOrNull(elementType) }

    private fun IrVararg.concreteVarargArrayTypeOrNull(): IrType? =
        type.concreteVarargArrayTypeOrNull(varargElementType)

    private fun IrType.concreteVarargArrayTypeOrNull(elementType: IrType): IrType? {
        if (elementType.containsTypeParameter()) return null
        return when {
            isSupportedDotNetPrimitiveArray() -> this
            isDotNetGenericArray() -> irBuiltIns.arrayClass.typeWith(elementType)
            else -> null
        }
    }

    private fun IrType.concreteProjectedArrayTypeOrNull(): IrType? {
        if (!isDotNetGenericArray()) return null
        val projection = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return null
        if (projection.variance == Variance.INVARIANT || projection.type.containsTypeParameter()) return null
        return irBuiltIns.arrayClass.typeWith(projection.type)
    }

    private fun IrType.containsTypeParameter(): Boolean {
        val simpleType = this as? IrSimpleType ?: return true
        if (simpleType.classifierOrNull is IrTypeParameterSymbol) return true
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.containsTypeParameter() != false
        }
    }

    private data class EvaluatedElement(
        val source: IrVarargElement,
        val value: IrVariable,
    )
}

private val OPTIMIZED_ARRAY_OF_NAMES = setOf(
    "arrayOf",
    "booleanArrayOf",
    "charArrayOf",
    "doubleArrayOf",
    "intArrayOf",
    "longArrayOf",
)

private fun IrFunction.isOptimizedDotNetArrayOf(): Boolean {
    val packageFragment = when (val declarationParent = parent) {
        is IrClass -> declarationParent.getPackageFragment()
        is IrPackageFragment -> declarationParent
        else -> return false
    }
    return packageFragment.packageFqName == StandardNames.BUILT_INS_PACKAGE_FQ_NAME &&
            name.asString() in OPTIMIZED_ARRAY_OF_NAMES &&
            hasShape(regularParameters = 1) &&
            parameters[0].isVararg
}
