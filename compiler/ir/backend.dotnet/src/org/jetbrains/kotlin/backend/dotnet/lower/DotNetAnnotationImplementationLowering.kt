/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationMemberGenerator
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationTransformer
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.isSupportedDotNetMarkerAnnotationClass
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isEquals
import org.jetbrains.kotlin.ir.util.isHashCode
import org.jetbrains.kotlin.ir.util.isToString
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * CLR custom attributes must be concrete System.Attribute subclasses. Reuse the mature JS
 * single-class adapter over the common annotation member generator: the original Kotlin marker
 * is both its runtime value and its physical CLR attribute class, so no wrapper identity appears.
 */
internal class DotNetAnnotationImplementationLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(Transformer(context, irFile))
    }

    private class Transformer(
        dotNetContext: DotNetBackendContext,
        irFile: IrFile,
    ) : AnnotationImplementationTransformer(dotNetContext, dotNetContext.symbolTable, irFile) {
        override fun visitConstructorCall(expression: IrConstructorCall): IrExpression = expression

        override fun chooseConstructor(implClass: IrClass, expression: IrConstructorCall): IrConstructor =
            compilationException("The .NET annotation lowering does not create implementation classes", implClass)

        override fun visitClassNew(declaration: IrClass): IrStatement {
            if (!declaration.isAnnotationClass) return super.visitClassNew(declaration)
            // Optional-expect JVM annotations and valued annotation classes remain logical KLIB
            // declarations in this tranche. In particular, do not partially materialize them as
            // CLR attributes merely because they occur in the Common stdlib source closure.
            if (!declaration.isSupportedDotNetMarkerAnnotationClass()) return declaration

            val inheritedFunctions = declaration.functions
            val equals = inheritedFunctions.singleOrNull { it.isEquals() }
            val hashCode = inheritedFunctions.singleOrNull { it.isHashCode() }
            val toString = inheritedFunctions.singleOrNull { it.isToString() }
            if (equals == null && hashCode == null && toString == null) {
                // FIR does not promise fake Any overrides on annotation declarations for every
                // frontend/source-set shape. The Common generator owns their declarations and
                // bodies in that case, just as it does for wrapper implementations on the JVM.
                implementGeneratedFunctions(declaration, declaration)
                declaration.addConstructorBodyForCompatibility()
                return declaration
            }
            check(equals != null && hashCode != null && toString != null) {
                "Annotation class has an incomplete Any-method set"
            }

            val generator = AnnotationImplementationMemberGenerator(
                context,
                symbolTable,
                declaration,
                nameForToString = "@" + declaration.fqNameWhenAvailable!!.asString(),
                forbidDirectFieldAccess = forbidDirectFieldAccessInMethods,
            ) { type, left, right -> generatedEquals(this, type, left, right) }

            val equalsFunction = equals
            val hashCodeFunction = hashCode
            val toStringFunction = toString
            for (function in listOf(equalsFunction, hashCodeFunction, toStringFunction)) {
                function.isFakeOverride = false
                function.dispatchReceiverParameter?.type = declaration.defaultType
            }

            generateFunctionBodies(
                declaration,
                declaration,
                equalsFunction,
                hashCodeFunction,
                toStringFunction,
                generator,
            )
            declaration.addConstructorBodyForCompatibility()
            return declaration
        }

        override fun getArrayContentEqualsSymbol(type: IrType): IrFunctionSymbol =
            compilationException("Valued annotation classes are outside the selected .NET tranche", type)

        override fun implementAnnotationPropertiesAndConstructor(
            implClass: IrClass,
            annotationClass: IrClass,
            generatedConstructor: IrConstructor,
        ) {
            compilationException("The .NET annotation lowering does not create implementation classes", implClass)
        }
    }
}
