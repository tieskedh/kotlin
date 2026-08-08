/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetClassifierInfo
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeClassifierKind
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibLibrary
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBody
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBodyKind
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isEnumClass
import org.jetbrains.kotlin.ir.util.isEnumEntry
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions

/** CLR-visible implementation constructor of a physically private enum-entry subclass. */
internal val DOTNET_ENUM_ENTRY_CONSTRUCTOR: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_ENUM_ENTRY_CONSTRUCTOR", isSynthetic = true)

/**
 * Lowers Kotlin enum classes to ordinary CLR reference classes while KLIB retains the logical enum
 * declaration. The constructor/entry transformation follows the JVM lowering; the fresh-array
 * `values()` body and name branches follow JS/Native where the CLR has no truthful `System.Enum`
 * identity for Kotlin reference entries.
 */
internal class DotNetEnumClassLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    private val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
    private val localEntryFields = linkedMapOf<IrEnumEntry, IrField>()
    private val externalEntryFields = hashMapOf<IrEnumEntry, IrField>()

    override fun lower(irModule: IrModuleFragment) {
        val enumClasses = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFile(declaration: IrFile) {
                if (!DotNetStdlibLibrary.isResolutionOnlySource(declaration)) {
                    declaration.acceptChildrenVoid(this)
                }
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isEnumClass) enumClasses += declaration
                declaration.acceptChildrenVoid(this)
            }
        })

        enumClasses
            .filterNot { enumClass ->
                enumClass.fileOrNull?.let(DotNetStdlibLibrary::isResolutionOnlySource) == true
            }
            .forEach { enumClass -> EnumClassTransformer(enumClass).run() }
        context.enumEntryFields.putAll(localEntryFields)

        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFile(declaration: IrFile): IrFile =
                if (DotNetStdlibLibrary.isResolutionOnlySource(declaration)) {
                    declaration
                } else {
                    super.visitFile(declaration)
                }

            // Annotation arguments are KLIB metadata, not executable enum reads. Runtime CLR
            // attributes are projected separately by DotNetAnnotationMetadata; descending here
            // would incorrectly demand physical fields for resolution-only builtin enums such
            // as DeprecationLevel.
            override fun visitAnnotation(expression: IrAnnotation): IrExpression = expression

            override fun visitGetEnumValue(expression: IrGetEnumValue): IrExpression {
                val entry = expression.symbol.owner
                val field = localEntryFields[entry]
                    ?: externalEntryFields.getOrPut(entry) { buildExternalEntryField(entry) }
                return IrGetFieldImpl(
                    expression.startOffset,
                    expression.endOffset,
                    field.symbol,
                    expression.type,
                )
            }
        })
    }

    private fun buildExternalEntryField(entry: IrEnumEntry): IrField {
        val enumClass = entry.parent as? IrClass
            ?: error("Internal .NET backend error: enum entry '${entry.name.asString()}' has no enum class")
        if (DotNetClassifierInfo.derive(enumClass).runtimeKind == DotNetRuntimeClassifierKind.K_VISIBILITY) {
            return context.irFactory.buildField {
                name = entry.name
                type = enumClass.defaultType
                origin = IrDeclarationOrigin.FIELD_FOR_ENUM_ENTRY
                visibility = DescriptorVisibilities.PUBLIC
                isFinal = true
                isStatic = true
            }.apply {
                parent = enumClass
            }
        }
        val binding = externalDeclarations.enumEntryOrNull(entry)
            ?: dotNetUnsupported(
                "enum entry '${(entry.parent as? IrClass)?.kotlinFqName}.${entry.name.asString()}' " +
                        "has no producer-recorded CLR field"
            )
        return context.irFactory.buildField {
            name = Name.identifier(binding.enumEntry.fieldName)
            type = enumClass.defaultType
            origin = IrDeclarationOrigin.FIELD_FOR_ENUM_ENTRY
            visibility = DescriptorVisibilities.PUBLIC
            isFinal = true
            isStatic = true
        }.apply {
            parent = enumClass
        }
    }

    private inner class EnumClassTransformer(private val irClass: IrClass) {
        private val loweredEnumConstructors = hashMapOf<IrConstructorSymbol, IrConstructor>()
        private val loweredEnumConstructorParameters = hashMapOf<IrValueParameterSymbol, IrValueParameter>()
        private val enumEntryOrdinals = hashMapOf<IrEnumEntry, Int>()
        private val declarationToEnumEntry = linkedMapOf<IrDeclaration, IrEnumEntry>()
        private val enumArrayType = context.irBuiltIns.arrayClass.typeWith(irClass.defaultType)
        private var entryArtifactsEnd = 0

        fun run() {
            val entries = irClass.declarations.filterIsInstance<IrEnumEntry>()
            entries.forEachIndexed { index, enumEntry ->
                enumEntryOrdinals[enumEntry] = index
                enumEntry.correspondingClass?.let { entryClass ->
                    entryClass.parent = irClass
                    declarationToEnumEntry[entryClass] = enumEntry
                }
                val field = buildEnumEntryField(enumEntry)
                declarationToEnumEntry[field] = enumEntry
                localEntryFields[enumEntry] = field
            }

            val rewrittenDeclarations = mutableListOf<IrDeclaration>()
            for (declaration in irClass.declarations) {
                if (declaration !is IrEnumEntry) {
                    rewrittenDeclarations += declaration
                    continue
                }
                rewrittenDeclarations += localEntryFields.getValue(declaration)
                declaration.correspondingClass?.let(rewrittenDeclarations::add)
                entryArtifactsEnd = rewrittenDeclarations.size
            }
            irClass.declarations.clear()
            irClass.declarations += rewrittenDeclarations

            val entriesField = if (irClass.hasGetEntriesFunction) buildEntriesField() else null
            if (entriesField != null) {
                irClass.declarations.add(entryArtifactsEnd, entriesField)
            }

            irClass.transformChildrenVoid(EnumClassDeclarationsTransformer(entriesField))
            irClass.transformChildrenVoid(EnumClassCallTransformer())

            // KLIB was captured before target lowering. From this point onward these are ordinary
            // CLR reference classes; keeping enum kinds would invite System.Enum/value-type codegen.
            entries.mapNotNull(IrEnumEntry::correspondingClass).forEach { it.kind = ClassKind.CLASS }
            if (irClass.declarations.any { declaration ->
                    declaration is IrSimpleFunction &&
                            !declaration.isFakeOverride &&
                            declaration.modality == Modality.ABSTRACT
                }
            ) {
                // Kotlin enum classes are source-final, but an enum with an abstract member is a
                // physical abstract base implemented by its private entry subclasses. CLR class
                // flags must state that fact; sealed plus an abstract MethodDef is invalid IL at
                // dispatch time even though ILAsm accepts it.
                irClass.modality = Modality.ABSTRACT
            }
            irClass.kind = ClassKind.CLASS
        }

        private val IrClass.hasGetEntriesFunction: Boolean
            get() = declarations.any { declaration ->
                when (declaration) {
                    is IrSimpleFunction -> declaration.hasEnumEntriesBody
                    // FIR retains the synthetic getter under its property, while the JVM
                    // psi2ir shape exposes the function directly. Accept both repository IR
                    // shapes and key the decision on the same authoritative synthetic body the
                    // transformer consumes below.
                    is IrProperty -> declaration.getter?.hasEnumEntriesBody == true
                    else -> false
                }
            }

        private val IrSimpleFunction.hasEnumEntriesBody: Boolean
            get() = (body as? IrSyntheticBody)?.kind == IrSyntheticBodyKind.ENUM_ENTRIES

        private fun buildEnumEntryField(enumEntry: IrEnumEntry): IrField =
            context.irFactory.buildField {
                startOffset = enumEntry.startOffset
                endOffset = enumEntry.endOffset
                name = enumEntry.name
                type = irClass.defaultType
                visibility = DescriptorVisibilities.PUBLIC
                origin = IrDeclarationOrigin.FIELD_FOR_ENUM_ENTRY
                isFinal = true
                isStatic = true
            }.apply {
                parent = irClass
                initializer = enumEntry.initializerExpression?.let { initializer ->
                    context.irFactory.createExpressionBody(
                        initializer.expression.patchDeclarationParents(this)
                    )
                }
                annotations = annotations + enumEntry.annotations
            }

        private fun buildEntriesField(): IrField = context.irFactory.buildField {
            name = Name.identifier("\$ENTRIES")
            type = context.symbols.enumEntries.owner.typeWith(irClass.defaultType)
            visibility = DescriptorVisibilities.PRIVATE
            origin = IrDeclarationOrigin.FIELD_FOR_ENUM_ENTRIES
            isFinal = true
            isStatic = true
        }.apply {
            parent = irClass
            initializer = context.createIrBuilder(symbol).run {
                irExprBody(
                    irCall(this@DotNetEnumClassLowering.context.symbols.createEnumEntries).apply {
                        typeArguments[0] = irClass.defaultType
                        arguments[0] = buildEnumArray()
                    }
                )
            }
        }

        private inner class EnumClassDeclarationsTransformer(
            private val entriesField: IrField?,
        ) : IrElementTransformerVoid() {
            override fun visitClass(declaration: IrClass): IrStatement =
                if (declaration.isEnumEntry) super.visitClass(declaration) else declaration

            override fun visitConstructor(declaration: IrConstructor): IrStatement =
                context.irFactory.buildConstructor {
                    updateFrom(declaration)
                    if (declaration.parentAsClass.isEnumEntry) {
                        origin = DOTNET_ENUM_ENTRY_CONSTRUCTOR
                    }
                    returnType = declaration.returnType
                }.apply {
                    parent = declaration.parent
                    annotations = declaration.annotations
                    addValueParameter("\$enum\$name", context.irBuiltIns.stringType)
                    addValueParameter("\$enum\$ordinal", context.irBuiltIns.intType)
                    parameters += declaration.parameters.map { parameter ->
                        parameter.copyTo(this).also { copied ->
                            loweredEnumConstructorParameters[parameter.symbol] = copied
                        }
                    }
                    body = declaration.body?.patchDeclarationParents(this)
                    loweredEnumConstructors[declaration.symbol] = this
                    metadata = declaration.metadata
                }

            override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
                val syntheticBody = declaration.body as? IrSyntheticBody ?: return declaration
                declaration.body = context.createIrBuilder(declaration.symbol).run {
                    irExprBody(
                        when (syntheticBody.kind) {
                            IrSyntheticBodyKind.ENUM_VALUES -> buildEnumArray()
                            IrSyntheticBodyKind.ENUM_VALUEOF -> buildValueOf(declaration)
                            IrSyntheticBodyKind.ENUM_ENTRIES -> irGetField(null, checkNotNull(entriesField))
                        }
                    )
                }
                return declaration
            }
        }

        private fun IrBuilderWithScope.buildValueOf(function: IrSimpleFunction): IrExpression {
            val nameParameter = function.parameters.single()
            val branches = buildList {
                for ([declaration, entry] in declarationToEnumEntry) {
                    val field = declaration as? IrField ?: continue
                    add(irBranch(
                        irEquals(irGet(nameParameter), irString(entry.name.asString())),
                        irGetField(null, field),
                    ))
                }
                add(irElseBranch(
                    irCall(context.irBuiltIns.illegalArgumentExceptionSymbol).apply {
                        arguments[0] = irConcat().apply {
                            addArgument(irString("No enum constant ${irClass.kotlinFqName}."))
                            addArgument(irGet(nameParameter))
                        }
                    }
                ))
            }
            return irWhen(irClass.defaultType, branches)
        }

        private fun IrBuilderWithScope.buildEnumArray(): IrExpression =
            irBlock(resultType = enumArrayType) {
                val result = irTemporary(
                    irCall(context.irBuiltIns.arrayOfNulls, enumArrayType).apply {
                        typeArguments[0] = irClass.defaultType
                        arguments[0] = irInt(localEntryFieldsForClass().size)
                        type = enumArrayType
                    },
                    nameHint = "<enumValues>",
                )
                val arraySet = context.irBuiltIns.arrayClass.owner.functions.single {
                    it.name == OperatorNameConventions.SET
                }
                localEntryFieldsForClass().forEachIndexed { index, field ->
                    +irCall(arraySet.symbol).apply {
                        arguments[0] = irGet(result)
                        arguments[1] = irInt(index)
                        arguments[2] = irGetField(null, field)
                    }
                }
                +irGet(result)
            }

        private fun localEntryFieldsForClass(): List<IrField> =
            enumEntryOrdinals.keys.sortedBy(enumEntryOrdinals::getValue).map(localEntryFields::getValue)

        private inner class EnumClassCallTransformer : IrElementTransformerVoidWithContext() {
            override fun visitClassNew(declaration: IrClass): IrStatement =
                if (declaration.isEnumEntry) super.visitClassNew(declaration) else declaration

            override fun visitGetValue(expression: IrGetValue): IrExpression =
                loweredEnumConstructorParameters[expression.symbol]?.let { parameter ->
                    IrGetValueImpl(
                        expression.startOffset,
                        expression.endOffset,
                        parameter.type,
                        parameter.symbol,
                        expression.origin,
                    )
                } ?: expression

            override fun visitSetValue(expression: IrSetValue): IrExpression {
                expression.transformChildrenVoid()
                return loweredEnumConstructorParameters[expression.symbol]?.let { parameter ->
                    IrSetValueImpl(
                        expression.startOffset,
                        expression.endOffset,
                        expression.type,
                        parameter.symbol,
                        expression.value,
                        expression.origin,
                    )
                } ?: expression
            }

            override fun visitEnumConstructorCall(expression: org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)
                val scopeOwnerSymbol = currentScope!!.scope.scopeOwnerSymbol
                return context.createIrBuilder(scopeOwnerSymbol).at(expression).run {
                    val constructor = loweredEnumConstructors[expression.symbol]
                        ?: expression.symbol.owner.bootstrapEnumPrimaryConstructor()
                    val call = if (scopeOwnerSymbol is IrConstructorSymbol) {
                        irDelegatingConstructorCall(constructor)
                    } else {
                        irCall(constructor)
                    }
                    call.also {
                        passConstructorArguments(
                            it,
                            expression,
                            declarationToEnumEntry[scopeOwnerSymbol.owner as IrDeclaration],
                        )
                    }
                }
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)
                val replacement = loweredEnumConstructors[expression.symbol] ?: return expression
                return context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression).run {
                    irDelegatingConstructorCall(replacement).also {
                        passConstructorArguments(it, expression)
                    }
                }
            }

            private fun IrBuilderWithScope.passConstructorArguments(
                call: IrFunctionAccessExpression,
                original: IrFunctionAccessExpression,
                enumEntry: IrEnumEntry? = null,
            ) {
                call.copyTypeArgumentsFrom(original)
                if (enumEntry != null) {
                    call.arguments[0] = irString(enumEntry.name.asString())
                    call.arguments[1] = irInt(enumEntryOrdinals.getValue(enumEntry))
                } else {
                    val constructor = currentScope!!.scope.scopeOwnerSymbol as IrConstructorSymbol
                    call.arguments[0] = irGet(constructor.owner.parameters[0])
                    call.arguments[1] = irGet(constructor.owner.parameters[1])
                }
                // Enum's base constructor has no user arguments. Installed KLIBs expose this as
                // an IR stub; the temporary source projection can instead contribute name,
                // ordinal, default-mask, and marker arguments. None belongs behind the exact
                // synthetic values above. Other enum constructors contain only their declared
                // user arguments and follow the JVM algorithm unchanged.
                val originalArguments = original.arguments.takeUnless {
                    original.symbol.owner.parentAsClass.symbol == context.irBuiltIns.enumClass
                }.orEmpty()
                for ([index, argument] in originalArguments.withIndex()) {
                    if (argument != null) call.arguments[index + 2] = argument
                }
            }

            /** Removes the frontend-only default-argument constructor from Enum's physical path. */
            private fun IrConstructor.bootstrapEnumPrimaryConstructor(): IrConstructor {
                if (parentAsClass.symbol != context.irBuiltIns.enumClass) return this
                if (parameters.none { it.name.asString().startsWith("\$mask") }) return this
                return parentAsClass.declarations.filterIsInstance<IrConstructor>().singleOrNull { candidate ->
                    candidate.parameters.size == 2 &&
                            candidate.parameters.none { it.name.asString().startsWith("\$mask") }
                } ?: error(
                    "Internal .NET backend error: projected kotlin.Enum default constructor has no " +
                            "single (name, ordinal) primary constructor"
                )
            }
        }
    }
}
