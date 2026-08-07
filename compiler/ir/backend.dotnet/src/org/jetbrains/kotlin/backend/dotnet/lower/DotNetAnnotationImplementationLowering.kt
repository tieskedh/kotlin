/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(org.jetbrains.kotlin.DeprecatedCompilerApi::class)

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.at
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
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.kClassReference
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.irAttribute
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
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedPropertySource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
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
        addCallableAnnotationFactories(irFile, transformer)
    }

    private fun IrClass.addRuntimeAnnotationFactory(transformer: Transformer) {
        if (isDotNetResolutionOnlyStdlibDeclaration) return
        val values = runtimeAnnotationValues(transformer)
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

    /**
     * Binds each rich reference to one declaration-owned producer before callable lowering erases
     * the reflection target. Property wrapper calls receive their own producer, independently of
     * the getter and setter references they contain.
     */
    private fun addCallableAnnotationFactories(irFile: IrFile, transformer: Transformer) {
        val kTypeBuilder = DotNetKTypeIrBuilder(context, operation = "callable signature")
        val references = mutableListOf<IrRichFunctionReference>()
        val propertyCalls = mutableListOf<IrCall>()
        irFile.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                when (element) {
                    is IrRichFunctionReference -> references += element
                    is IrCall -> if (element.dotNetPropertyAnnotationOwner != null) propertyCalls += element
                }
                element.acceptChildrenVoid(this)
            }
        })

        val factories = IdentityHashMap<IrAnnotationContainer, IrSimpleFunction>()
        val emptyOwners = Collections.newSetFromMap(IdentityHashMap<IrAnnotationContainer, Boolean>())
        val generated = mutableListOf<IrSimpleFunction>()

        fun factoryFor(owner: IrAnnotationContainer): IrSimpleFunction? {
            factories[owner]?.let { return it }
            if (owner in emptyOwners) return null
            val factory = owner.createCallableAnnotationFactoryOrNull(irFile, transformer, generated.size)
            if (factory == null) emptyOwners += owner else {
                factories[owner] = factory
                generated += factory
            }
            return factory
        }

        references.forEach { reference ->
            val owner = reference.reflectionTargetSymbol?.owner as? IrAnnotationContainer ?: return@forEach
            reference.dotNetCallableAnnotationFactory = factoryFor(owner)
            if (!context.hasCallableParameterSurface) return@forEach
            val target = owner as? IrFunction ?: return@forEach
            val builder = context.createIrBuilder(reference.invokeFunction.symbol).at(reference)
            val declaredParameters = if (target is IrConstructor) {
                (target.parent as? IrClass)?.typeParameters
                    ?: error("Internal .NET backend error: reflected constructor has no class owner")
            } else {
                target.typeParameters
            }
            val descriptors = target.callableParameterDescriptors(
                reference.boundValues.size,
                builder,
                ::factoryFor,
            )
            reference.dotNetCallableSignature = kTypeBuilder.run {
                builder.buildCallableSignature(target.returnType, declaredParameters, descriptors)
            }
        }
        propertyCalls.forEach { call ->
            val owner = call.dotNetPropertyAnnotationOwner
            val producer = owner?.let(::factoryFor)
            call.arguments[call.arguments.lastIndex] = context.createIrBuilder(call.symbol).run {
                irCall(producer ?: this@DotNetAnnotationImplementationLowering.context.callableAnnotationSymbols.empty)
            }
            if (!context.hasCallableParameterSurface) return@forEach
            val builder = context.createIrBuilder(call.symbol).at(call)
            val property = call.dotNetPropertySignatureOwner
            if (property != null) {
                val getter = property.getter
                    ?: error("Internal .NET backend error: reflected property '${property.name}' has no getter")
                val descriptors = getter.callableParameterDescriptors(
                    call.dotNetPropertyBoundReceiverCount ?: 0,
                    builder,
                    ::factoryFor,
                )
                call.arguments[call.arguments.lastIndex - 2] = kTypeBuilder.run {
                    builder.buildCallableSignature(getter.returnType, getter.typeParameters, descriptors)
                }
            } else {
                val localType = call.dotNetLocalPropertySignatureType ?: return@forEach
                call.arguments[call.arguments.lastIndex - 2] = kTypeBuilder.run {
                    builder.buildCallableSignature(localType, emptyList(), emptyList())
                }
            }
        }
        irFile.declarations += generated
    }

    private fun IrFunction.callableParameterDescriptors(
        boundReceiverCount: Int,
        builder: org.jetbrains.kotlin.ir.builders.IrBuilderWithScope,
        factoryFor: (IrAnnotationContainer) -> IrSimpleFunction?,
    ): List<DotNetCallableParameterDescriptor> {
        val receivers = mutableListOf<DotNetCallableParameterDescriptor>()
        val values = mutableListOf<DotNetCallableParameterDescriptor>()
        parameters.forEach { parameter ->
            val descriptor = DotNetCallableParameterDescriptor(
                name = parameter.reflectionName(),
                type = parameter.type,
                kind = parameter.reflectionKind(),
                isOptional = parameter.hasKotlinOptionalSemantics(),
                isVararg = parameter.varargElementType != null,
                annotations = builder.irCall(
                    factoryFor(parameter) ?: context.callableAnnotationSymbols.empty
                ),
            )
            if (parameter.kind == IrParameterKind.Regular) values += descriptor else receivers += descriptor
        }
        require(boundReceiverCount <= receivers.size) {
            "Internal .NET backend error: callable '$name' captures $boundReceiverCount receivers " +
                    "but declares only ${receivers.size}"
        }
        return receivers.drop(boundReceiverCount) + values
    }

    private fun IrValueParameter.reflectionName(): String? {
        if (kind == IrParameterKind.DispatchReceiver || kind == IrParameterKind.ExtensionReceiver) return null
        val foreign = foreignMethodAndIndexOrNull()
        if (foreign != null) {
            val sourceAndMethod = foreign.first
            val index = foreign.second
            val rows = sourceAndMethod.first.assembly.metadata.parameterDefinitions.filter { parameter ->
                parameter.declaringMethod == sourceAndMethod.second.handle && parameter.parameterIndex == index
            }
            return rows.singleOrNull()?.name?.takeIf(Name::isValidIdentifier)
        }
        return name.asString().takeUnless { name.isSpecial }
    }

    private fun IrValueParameter.reflectionKind(): Int = when (kind) {
        IrParameterKind.DispatchReceiver -> PARAMETER_INSTANCE
        IrParameterKind.Context -> PARAMETER_CONTEXT
        IrParameterKind.ExtensionReceiver -> PARAMETER_EXTENSION
        IrParameterKind.Regular -> PARAMETER_VALUE
    }

    private fun IrValueParameter.hasKotlinOptionalSemantics(
        visited: MutableSet<IrSimpleFunction> = Collections.newSetFromMap(IdentityHashMap()),
    ): Boolean {
        val function = parent as? IrSimpleFunction ?: return defaultValue != null
        if (function.containerSource is DotNetClrImportedMethodSource ||
            function.containerSource is DotNetClrImportedPropertySource
        ) return false
        if (defaultValue != null) return true
        if (!visited.add(function) || kind != IrParameterKind.Regular) return false
        val regularIndex = function.parameters.filter { it.kind == IrParameterKind.Regular }.indexOf(this)
        if (regularIndex < 0) return false
        return function.overriddenSymbols.any { overridden ->
            overridden.owner.parameters.filter { it.kind == IrParameterKind.Regular }
                .getOrNull(regularIndex)
                ?.hasKotlinOptionalSemantics(visited) == true
        }
    }

    private fun IrValueParameter.foreignMethodAndIndexOrNull():
            Pair<Pair<org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource, DotNetClrMethodDefinition>, Int>? {
        val function = parent as? IrSimpleFunction ?: return null
        val method = when (val source = function.containerSource) {
            is DotNetClrImportedMethodSource -> source to source.method
            is DotNetClrImportedPropertySource -> {
                val property = function.correspondingPropertySymbol?.owner ?: return null
                source to when (function) {
                    property.getter -> source.getter
                    property.setter -> source.setter ?: return null
                    else -> return null
                }
            }
            else -> return null
        }
        val index = function.parameters.filter { it.kind == IrParameterKind.Regular }.indexOf(this)
        if (index < 0) return null
        return method to index
    }

    private fun IrAnnotationContainer.createCallableAnnotationFactoryOrNull(
        irFile: IrFile,
        transformer: Transformer,
        index: Int,
    ): IrSimpleFunction? {
        val foreign = foreignAnnotationMemberOrNull()
        val values = if (foreign == null) runtimeAnnotationValues(transformer) else emptyList()
        if (foreign == null && values.isEmpty()) return null

        val returnType = context.irBuiltIns.listClass.typeWith(context.irBuiltIns.annotationType)
        return context.irFactory.buildFun {
            startOffset = (this@createCallableAnnotationFactoryOrNull as? IrElement)?.startOffset ?: UNDEFINED_OFFSET
            endOffset = (this@createCallableAnnotationFactoryOrNull as? IrElement)?.endOffset ?: UNDEFINED_OFFSET
            origin = ANNOTATION_IMPLEMENTATION
            name = Name.special("<GetCallableAnnotations-$index>")
            this.returnType = returnType
            visibility = DescriptorVisibilities.PRIVATE
        }.apply factory@{
            parent = irFile
            body = context.createIrBuilder(symbol).irBlockBody {
                val result = if (foreign != null) {
                    irCall(this@DotNetAnnotationImplementationLowering.context.callableAnnotationSymbols.foreign).apply {
                        arguments[0] = kClassReference(foreign.owner.defaultType)
                        arguments[1] = irInt(foreign.metadataToken)
                        arguments[2] = irInt(foreign.kind)
                        arguments[3] = irInt(foreign.parameterIndex)
                    }
                } else {
                    val annotationType = context.irBuiltIns.annotationType
                    val arrayType = context.irBuiltIns.arrayClass.typeWith(annotationType)
                    irCall(this@DotNetAnnotationImplementationLowering.context.callableAnnotationSymbols.create).apply {
                        arguments[0] = IrVarargImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            arrayType,
                            annotationType,
                            values,
                        )
                    }
                }
                +irReturn(result)
            }
        }
    }

    private fun IrAnnotationContainer.runtimeAnnotationValues(transformer: Transformer): List<IrExpression> =
        annotations.mapNotNull { annotation ->
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

    private fun IrAnnotationContainer.foreignAnnotationMemberOrNull(): ForeignAnnotationMember? = when (this) {
        is IrProperty -> {
            val source = containerSource as? DotNetClrImportedPropertySource ?: return null
            ForeignAnnotationMember(
                owner = parent as? IrClass ?: return null,
                metadataToken = source.property.handle.token,
                kind = FOREIGN_PROPERTY,
            )
        }
        is IrSimpleFunction -> when (val source = containerSource) {
            is DotNetClrImportedMethodSource -> ForeignAnnotationMember(
                owner = parent as? IrClass ?: return null,
                metadataToken = source.method.handle.token,
                kind = FOREIGN_METHOD,
            )
            is DotNetClrImportedPropertySource -> {
                val property = correspondingPropertySymbol?.owner ?: return null
                val method = when (this) {
                    property.getter -> source.getter
                    property.setter -> source.setter ?: return null
                    else -> return null
                }
                ForeignAnnotationMember(
                    owner = parent as? IrClass ?: return null,
                    metadataToken = method.handle.token,
                    kind = FOREIGN_METHOD,
                )
            }
            else -> null
        }
        is IrValueParameter -> {
            val foreign = foreignMethodAndIndexOrNull() ?: return null
            val method = foreign.first.second
            val index = foreign.second
            ForeignAnnotationMember(
                owner = (parent as? IrFunction)?.parent as? IrClass ?: return null,
                metadataToken = method.handle.token,
                kind = FOREIGN_PARAMETER,
                parameterIndex = index,
            )
        }
        else -> null
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
        const val FOREIGN_METHOD = 0
        const val FOREIGN_PROPERTY = 1
        const val FOREIGN_PARAMETER = 2
        const val PARAMETER_INSTANCE = 0
        const val PARAMETER_CONTEXT = 1
        const val PARAMETER_EXTENSION = 2
        const val PARAMETER_VALUE = 3

        /** Built-in meta declarations are frontend/KLIB facts until their own runtime objects exist. */
        val resolutionOnlyMetaAnnotations = setOf(
            StandardNames.FqNames.target,
            StandardNames.FqNames.retention,
            StandardNames.FqNames.repeatable,
            StandardNames.FqNames.mustBeDocumented,
        )
    }

    private data class ForeignAnnotationMember(
        val owner: IrClass,
        val metadataToken: Int,
        val kind: Int,
        val parameterIndex: Int = -1,
    )

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

/** Private factory derived before callable lowering removes the declaration target. */
internal var IrRichFunctionReference.dotNetCallableAnnotationFactory: IrSimpleFunction?
    by irAttribute(copyByDefault = false)

/** Complete pre-vararg callable signature built beside parameter annotation factories. */
internal var IrRichFunctionReference.dotNetCallableSignature: IrExpression?
    by irAttribute(copyByDefault = false)
