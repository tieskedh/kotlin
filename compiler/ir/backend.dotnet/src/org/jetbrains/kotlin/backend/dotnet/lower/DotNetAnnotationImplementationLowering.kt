/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(org.jetbrains.kotlin.DeprecatedCompilerApi::class)

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationMemberGenerator
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationTransformer
import org.jetbrains.kotlin.backend.common.lower.ANNOTATION_IMPLEMENTATION
import org.jetbrains.kotlin.backend.common.lower.MethodsFromAnyGeneratorForLowerings
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetKClassRuntime
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetRuntimeRetainedAnnotation
import org.jetbrains.kotlin.backend.dotnet.isSupportedDotNetAnnotationClass
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.deepCopyWithoutPatchingParents
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isEquals
import org.jetbrains.kotlin.ir.util.isHashCode
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isToString
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

/**
 * CLR custom attributes must be concrete System.Attribute subclasses. Reuse the mature JS
 * single-class adapter over the common annotation member generator: the original Kotlin annotation
 * is both its runtime value and its physical CLR attribute class, so no wrapper identity appears.
 */
internal class DotNetAnnotationImplementationLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        val attachedAnnotations = Collections.newSetFromMap(IdentityHashMap<IrAnnotation, Boolean>())
        val classes = mutableListOf<IrClass>()
        irFile.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrAnnotationContainer) attachedAnnotations += element.annotations
                if (element is IrClass) classes += element
                element.acceptChildrenVoid(this)
            }
        })

        val transformer = Transformer(context, irFile, attachedAnnotations)
        irFile.transformChildrenVoid(transformer)

        // Annotations intentionally are not ordinary IR children. Visit every annotation
        // container explicitly, as the Common actualizer does, while constructor defaults are
        // still present. The later default-parameter cleaner may then remove declaration bodies
        // without weakening exact CLR custom-attribute projection.
        irFile.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrAnnotationContainer) {
                    element.annotations.forEach(transformer::materializeDefaults)
                }
                element.acceptChildrenVoid(this)
            }
        })

        // The runtime factory is executable, derived metadata. Copy each authoritative attached
        // application only after defaults have been materialized, then feed that copy through
        // the same ordinary annotation-construction transformer as source expressions. The
        // attached KLIB application itself remains untouched and continues to own declaration
        // identity, retention, use-site, and round-trip information.
        classes.forEach { irClass -> irClass.addRuntimeAnnotationFactory(transformer) }
    }

    private fun IrClass.addRuntimeAnnotationFactory(transformer: Transformer) {
        if (isDotNetResolutionOnlyStdlibDeclaration) return
        val values = annotations.mapNotNull { annotation ->
            val annotationClass = annotation.classSymbol.owner
            if (!annotationClass.isSupportedDotNetAnnotationClass() ||
                !annotationClass.isDotNetRuntimeRetainedAnnotation() ||
                annotationClass.isDotNetResolutionOnlyStdlibDeclaration ||
                annotationClass.fqNameWhenAvailable in resolutionOnlyMetaAnnotations ||
                annotation.arguments.filterNotNull().any { value -> !value.hasExecutableAnnotationValue() }
            ) {
                return@mapNotNull null
            }
            annotation.deepCopyWithoutPatchingParents().transform(transformer, null)
        }
        if (values.isEmpty()) return

        val holder = annotationFactoryHolder()
        val annotationType = context.irBuiltIns.annotationType
        val arrayType = context.irBuiltIns.arrayClass.typeWith(annotationType)
        val factory = context.irFactory.buildFun {
            startOffset = this@addRuntimeAnnotationFactory.startOffset
            endOffset = this@addRuntimeAnnotationFactory.endOffset
            origin = ANNOTATION_IMPLEMENTATION
            name = Name.special(DotNetKClassRuntime.ANNOTATION_FACTORY_METHOD_NAME)
            returnType = arrayType
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            parent = holder
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(
                    IrVarargImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        arrayType,
                        annotationType,
                        values,
                    )
                )
            }
        }
        holder.declarations += factory
    }

    private fun IrClass.annotationFactoryHolder(): IrClass {
        context.companionStaticOwners[this]?.takeIf { it !== this }?.let { return it }
        declarations.filterIsInstance<IrClass>()
            .firstOrNull { nested ->
                nested.origin == DOTNET_STATIC_HOLDER &&
                        nested.name.asString() in setOf(
                            DotNetKClassRuntime.ANNOTATION_FACTORY_HOLDER_NAME,
                            DotNetKClassRuntime.COMPANION_STATICS_HOLDER_NAME,
                        )
            }
            ?.let { return it }

        val owner = this
        val holder = context.irFactory.buildClass {
            startOffset = owner.startOffset
            endOffset = owner.endOffset
            origin = DOTNET_STATIC_HOLDER
            name = Name.special(DotNetKClassRuntime.ANNOTATION_FACTORY_HOLDER_NAME)
            kind = ClassKind.CLASS
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            parent = owner
            superTypes = listOf(context.irBuiltIns.anyType)
            createThisReceiverParameter()
        }
        declarations += holder
        // Interfaces and logically generic classes need one non-generic holder for any later
        // static state as well. Reusing this owner prevents the static-initialization pass from
        // introducing a competing physical holder after annotation discovery has been derived.
        if (isInterface || typeParameters.isNotEmpty()) {
            context.companionStaticOwners.putIfAbsent(this, holder)
        }
        return holder
    }

    private fun IrExpression.hasExecutableAnnotationValue(): Boolean = when (this) {
        is IrAnnotation -> {
            val annotationClass = classSymbol.owner
            annotationClass.isSupportedDotNetAnnotationClass() &&
                    !annotationClass.isDotNetResolutionOnlyStdlibDeclaration &&
                    annotationClass.fqNameWhenAvailable !in resolutionOnlyMetaAnnotations &&
                    arguments.filterNotNull().all { value -> value.hasExecutableAnnotationValue() }
        }
        is IrGetEnumValue ->
            (symbol.owner.parent as? IrClass)?.isDotNetResolutionOnlyStdlibDeclaration != true
        is IrVararg -> elements.filterIsInstance<IrExpression>().all { value -> value.hasExecutableAnnotationValue() }
        else -> true
    }

    private companion object {
        /** Built-in meta declarations are frontend/KLIB facts until their own runtime objects exist. */
        val resolutionOnlyMetaAnnotations = setOf(
            StandardNames.FqNames.target,
            StandardNames.FqNames.retention,
            StandardNames.FqNames.repeatable,
            StandardNames.FqNames.mustBeDocumented,
        )
    }

    private class Transformer(
        private val dotNetContext: DotNetBackendContext,
        irFile: IrFile,
        private val attachedAnnotations: Set<IrAnnotation>,
    ) : AnnotationImplementationTransformer(dotNetContext, dotNetContext.symbolTable, irFile) {
        override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
            if (expression !is IrAnnotation) return expression
            materializeDefaults(expression)
            if (expression in attachedAnnotations) return expression

            // Unlike JVM, .NET uses the original concrete annotation class for both ordinary
            // Kotlin construction and attached metadata. Turn an executable annotation node
            // into an ordinary call to that same constructor so subsequent target lowerings can
            // normalize its arrays, enums, defaults, and nested expressions without descending
            // into attached KLIB metadata or synthesizing a second implementation class.
            return IrConstructorCallImpl.fromSymbolOwner(
                expression.startOffset,
                expression.endOffset,
                expression.type,
                expression.symbol,
                expression.origin,
            ).also { call ->
                call.typeArguments.indices.forEach { index ->
                    call.typeArguments[index] = expression.typeArguments[index]
                }
                call.arguments.indices.forEach { index ->
                    call.arguments[index] = expression.arguments[index]
                }
                call.transformChildrenVoid(this)
            }
        }

        fun materializeDefaults(annotation: IrAnnotation) {
            for (parameter in annotation.symbol.owner.parameters) {
                val index = parameter.indexInParameters
                if (annotation.arguments[index] == null) {
                    annotation.arguments[index] =
                        parameter.defaultValue?.expression?.deepCopyWithoutPatchingParents()
                }
                annotation.arguments[index]?.normalizeAnnotationValue(parameter.type)
            }
        }

        private fun IrExpression.normalizeAnnotationValue(expectedType: IrType) {
            when (this) {
                is IrAnnotation -> materializeDefaults(this)
                is IrVararg -> {
                    // Serialized annotation defaults can conservatively restore a literal as
                    // Array<Any?> even though the annotation member retains Array<E>. JVM
                    // annotation codegen consumes the declared member type directly; executable
                    // .NET annotations first become ordinary constructor calls, so restore the
                    // same authoritative type before the general vararg lowering allocates its
                    // CLR vector. This is not inference from values and also covers empty arrays.
                    if (expectedType.isArray()) {
                        val elementType = (expectedType as? IrSimpleType)
                            ?.arguments
                            ?.singleOrNull()
                            ?.typeOrNull
                            ?: compilationException(
                                "Annotation array member has no concrete element type: $expectedType",
                                this,
                            )
                        type = expectedType
                        varargElementType = elementType
                        elements.filterIsInstance<IrExpression>()
                            .forEach { element -> element.normalizeAnnotationValue(elementType) }
                    } else {
                        elements.filterIsInstance<IrExpression>()
                            .forEach { element -> element.normalizeNestedAnnotationDefaults() }
                    }
                }
            }
        }

        private fun IrExpression.normalizeNestedAnnotationDefaults() {
            if (this is IrAnnotation) materializeDefaults(this)
        }

        override fun chooseConstructor(implClass: IrClass, expression: IrConstructorCall): IrConstructor =
            compilationException("The .NET annotation lowering does not create implementation classes", implClass)

        override fun visitClassNew(declaration: IrClass): IrStatement {
            if (!declaration.isAnnotationClass) return super.visitClassNew(declaration)
            // Optional expect declarations have no physical class. Every actual declaration uses
            // the same concrete runtime object for construction and Kotlin annotation semantics;
            // its independently selected CLR custom-attribute projection may be narrower.
            if (!declaration.isSupportedDotNetAnnotationClass()) return declaration

            val inheritedFunctions = declaration.functions
            val equals = inheritedFunctions.singleOrNull { it.isEquals() }
            val hashCode = inheritedFunctions.singleOrNull { it.isHashCode() }
            val toString = inheritedFunctions.singleOrNull { it.isToString() }
            if (equals == null && hashCode == null && toString == null) {
                // FIR does not promise fake Any overrides on annotation declarations for every
                // frontend/source-set shape. The Common generator owns their declarations and
                // bodies in that case, just as it does for wrapper implementations on the JVM.
                // Create those declarations here so floating annotation equality still uses the
                // JVM total-order rule rather than ordinary IEEE `==`.
                val creator = MethodsFromAnyGeneratorForLowerings(
                    context,
                    declaration,
                    ANNOTATION_IMPLEMENTATION,
                )
                val generatedEquals = creator.createEqualsMethodDeclaration()
                val generatedHashCode = creator.createHashCodeMethodDeclaration()
                val generatedToString = creator.createToStringMethodDeclaration()
                val generator = AnnotationImplementationMemberGenerator(
                    context,
                    symbolTable,
                    declaration,
                    nameForToString = "@" + declaration.fqNameWhenAvailable!!.asString(),
                    forbidDirectFieldAccess = forbidDirectFieldAccessInMethods,
                ) { type, left, right -> generatedDotNetAnnotationEquals(this, type, left, right) }
                generateFunctionBodies(
                    declaration,
                    declaration,
                    generatedEquals,
                    generatedHashCode,
                    generatedToString,
                    generator,
                )
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
            ) { type, left, right -> generatedDotNetAnnotationEquals(this, type, left, right) }

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

        override fun getArrayContentEqualsSymbol(type: IrType): IrFunctionSymbol = when {
            type.isPrimitiveArray() -> dotNetContext.symbols.arraysContentEquals[type]
            type.isArray() -> dotNetContext.symbols.arraysContentEquals.entries
                .singleOrNull { entry -> entry.key.isArray() }
                ?.value
            else -> null
        } ?: compilationException("No Common contentEquals overload for annotation value type $type", type)

        private fun generatedDotNetAnnotationEquals(
            builder: IrBlockBodyBuilder,
            type: IrType,
            left: IrExpression,
            right: IrExpression,
        ): IrExpression = when {
            type.isFloat() -> builder.irCall(dotNetContext.symbols.dotNetAnnotationFloatEquals).apply {
                arguments[0] = left
                arguments[1] = right
            }
            type.isDouble() -> builder.irCall(dotNetContext.symbols.dotNetAnnotationDoubleEquals).apply {
                arguments[0] = left
                arguments[1] = right
            }
            else -> generatedEquals(builder, type, left, right)
        }

        override fun implementAnnotationPropertiesAndConstructor(
            implClass: IrClass,
            annotationClass: IrClass,
            generatedConstructor: IrConstructor,
        ) {
            compilationException("The .NET annotation lowering does not create implementation classes", implClass)
        }
    }
}
