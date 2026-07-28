/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irCatch
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredStaticInitializationFailure
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irComposite
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_STATIC_INITIALIZATION_FAILURE_STATE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_STATIC_INITIALIZATION_FAILURE_STATE")

/**
 * Restores Kotlin's static-initialization failure contract on top of CLR type initializers.
 *
 * JVM receives this contract from the VM: a non-[Error] first failure becomes
 * `ExceptionInInitializerError`, the original [Error] survives by identity, and later active
 * uses fail with `NoClassDefFoundError`. The web backends implement the same state machine in
 * their static-initializer declaration and usage lowerings.
 *
 * CLR `.cctor` failure cannot be used directly: the runtime replaces the first observable
 * failure with `TypeInitializationException` and permanently poisons the physical type. Keep
 * `.cctor` for its once-only synchronization and publication, but catch every Kotlin-owned
 * failure inside it and publish a private per-owner state object. The stable
 * `<EnsureInitialized>` method then asks the runtime state object for the first or later Kotlin
 * exception. Calls inserted at Kotlin active-use sites make that classification observable.
 *
 * Foreign CLR type initializers deliberately remain untouched and retain ordinary CLR
 * semantics. This lowering only owns Kotlin-generated class and file `<clinit>` functions.
 */
internal class DotNetStaticInitializationFailureLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
    private val localObjectFields: Map<IrField, IrClass>
        get() = context.objectInstanceFields.entries.associate { mapEntry ->
            mapEntry.value to mapEntry.key
        }
    private val physicalEntries = linkedMapOf<IrClass, IrSimpleFunction>()
    private val fileEntries = linkedMapOf<IrFile, IrSimpleFunction>()

    override fun lower(irModule: IrModuleFragment) {
        for (mapEntry in context.staticInitializations) {
            val initialization = mapEntry.value
            physicalEntries.putIfAbsent(initialization.physicalOwner, initialization.entry)
        }
        for (mapEntry in physicalEntries) {
            val physicalOwner = mapEntry.key
            val entry = mapEntry.value
            val initializer = physicalOwner.staticInitializerOrNull()
                ?: error(
                    "Internal .NET backend error: static-initialization entry " +
                            "'${entry.name.asString()}' has no physical initializer"
                )
            instrumentOwner(physicalOwner, initializer, entry, classifierName(physicalOwner))
        }
        for (file in irModule.files) {
            val initializer = file.staticInitializerOrNull() ?: continue
            val entry = createFileEntry(file)
            file.declarations.add(file.declarations.indexOf(initializer), entry)
            fileEntries[file] = entry
            instrumentOwner(file, initializer, entry, null)
        }

        insertActiveUsePrologues(irModule)
        insertSingletonActiveUseBarriers(irModule)
    }

    private fun instrumentOwner(
        owner: IrDeclarationParent,
        initializer: IrSimpleFunction,
        entry: IrSimpleFunction,
        className: String?,
    ) {
        val failureState = context.irFactory.buildField {
            name = Name.special("<static-initialization-failure>")
            type = context.irBuiltIns.anyNType
            visibility = DescriptorVisibilities.PRIVATE
            origin = DOTNET_STATIC_INITIALIZATION_FAILURE_STATE
            isFinal = false
            isStatic = true
        }.apply {
            parent = owner
        }
        if (owner is IrClass) {
            owner.declarations.add(owner.declarations.indexOf(initializer), failureState)
        }

        wrapInitializer(initializer, failureState)
        populateEntry(entry, failureState, className)
        context.staticInitializationFailures[owner] =
            DotNetLoweredStaticInitializationFailure(entry, failureState)
    }

    private fun wrapInitializer(initializer: IrSimpleFunction, failureState: IrField) {
        val body = initializer.body as? IrBlockBody
            ?: error("Internal .NET backend error: CLR static initializer has no block body")
        val originalStatements = body.statements.toList()
        val builder = context.createIrBuilder(initializer.symbol)
        val allInitializers = builder.irComposite(resultType = context.irBuiltIns.unitType) {
            +originalStatements
        }
        val catchParameter = builder.scope.createTemporaryVariableDeclaration(
            irType = context.irBuiltIns.throwableType,
            nameHint = "reason",
            origin = IrDeclarationOrigin.CATCH_PARAMETER,
            startOffset = initializer.startOffset,
            endOffset = initializer.endOffset,
            inventUniqueName = false,
        )
        val capture = builder.irCall(context.symbols.captureStaticInitializationFailure).apply {
            arguments[0] = builder.irGet(catchParameter)
        }
        val catchResult = builder.irComposite(resultType = context.irBuiltIns.unitType) {
            +builder.irSetField(
                null,
                failureState,
                capture,
            )
        }
        body.statements.clear()
        body.statements += builder.irTry(
            context.irBuiltIns.unitType,
            allInitializers,
            listOf(builder.irCatch(catchParameter, catchResult)),
            null,
        )
    }

    private fun populateEntry(entry: IrSimpleFunction, failureState: IrField, className: String?) {
        val builder = context.createIrBuilder(entry.symbol)
        val failureReason = builder.irCall(context.symbols.observeStaticInitializationFailure).apply {
            arguments[0] = builder.irImplicitCast(
                builder.irGetField(null, failureState),
                context.irBuiltIns.anyType,
            )
        }
        val failure = builder.irCall(context.symbols.staticInitializationFailure).apply {
            arguments[0] = failureReason
            arguments[1] = className?.let(builder::irString) ?: builder.irNull()
        }
        entry.body = builder.irBlockBody {
            +builder.irIfThen(
                builder.irNotEquals(builder.irGetField(null, failureState), builder.irNull()),
                failure,
            )
        }
    }

    private fun createFileEntry(file: IrFile): IrSimpleFunction =
        context.irFactory.buildFun {
            name = Name.special("<EnsureInitialized>")
            returnType = context.irBuiltIns.unitType
            visibility = DescriptorVisibilities.PRIVATE
            origin = DOTNET_STATIC_INITIALIZATION_ENTRY
        }.apply {
            parent = file
        }

    private fun insertActiveUsePrologues(irModule: IrModuleFragment) {
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                val body = declaration.body as? IrBlockBody
                val entry = body?.let { activeUseEntryFor(declaration) }
                if (entry != null) {
                    body.statements.add(0, context.createIrBuilder(declaration.symbol).irCall(entry.symbol))
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }

    private fun activeUseEntryFor(function: IrFunction): IrSimpleFunction? {
        if (
            function.origin == DOTNET_STATIC_INITIALIZER ||
            function.origin == DOTNET_STATIC_INITIALIZATION_ENTRY
        ) {
            return null
        }
        val parent = function.callableParent()
        return when {
            function is IrConstructor ->
                (parent as? IrClass)?.let { context.staticInitializations[it]?.entry }
            function is IrSimpleFunction && function.dispatchReceiverParameter == null ->
                when (parent) {
                    is IrClass -> physicalEntries[parent]
                    is IrFile -> fileEntries[parent]
                    else -> null
                }
            else -> null
        }
    }

    private fun IrFunction.callableParent(): IrDeclarationParent =
        when (val directParent = parent) {
            is IrProperty -> directParent.parent
            else -> directParent
        }

    private fun insertSingletonActiveUseBarriers(irModule: IrModuleFragment) {
        val localFields = localObjectFields
        var currentFunction: IrFunction? = null
        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFunction(declaration: IrFunction): IrStatement {
                val previousFunction = currentFunction
                currentFunction = declaration
                declaration.transformChildrenVoid(this)
                currentFunction = previousFunction
                return declaration
            }

            override fun visitGetField(expression: IrGetField): IrExpression {
                expression.transformChildrenVoid(this)
                val field = expression.symbol.owner
                if (field.origin != IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE) return expression
                val logicalOwner = singletonLogicalOwner(field, localFields) ?: return expression
                val entry = localStaticInitializationEntry(logicalOwner)
                    ?: externalStaticInitializationEntry(logicalOwner)
                    ?: return expression
                val function = currentFunction ?: return expression
                val builder = context.createIrBuilder(
                    function.symbol,
                    expression.startOffset,
                    expression.endOffset,
                )
                return builder.irComposite(resultType = expression.type) {
                    +builder.irCall(entry.symbol)
                    +expression
                }
            }
        })
    }

    private fun singletonLogicalOwner(field: IrField, localFields: Map<IrField, IrClass>): IrClass? {
        val singleton = localFields[field] ?: field.parent as? IrClass ?: return null
        return if (singleton.isCompanion) singleton.parent as? IrClass else singleton
    }

    private fun localStaticInitializationEntry(logicalOwner: IrClass): IrSimpleFunction? =
        context.staticInitializations[logicalOwner]?.entry

    private fun externalStaticInitializationEntry(logicalOwner: IrClass): IrSimpleFunction? {
        context.externalStaticInitializationEntries[logicalOwner]?.let { return it }
        val binding = externalDeclarations.staticInitializationOrNull(logicalOwner) ?: return null
        return createExternalEntry(logicalOwner).also { entry ->
            context.externalStaticInitializationEntries[logicalOwner] = entry
            context.externalStaticInitializations[entry] = binding
        }
    }

    private fun createExternalEntry(
        logicalOwner: IrClass,
    ): IrSimpleFunction =
        context.irFactory.buildFun {
            name = Name.special("<EnsureInitialized>")
            returnType = context.irBuiltIns.unitType
            visibility = DescriptorVisibilities.PUBLIC
            origin = DOTNET_STATIC_INITIALIZATION_ENTRY
        }.apply {
            parent = logicalOwner
        }

    private fun classifierName(physicalOwner: IrClass): String {
        val logicalOwner = context.staticInitializations.entries
            .firstOrNull { mapEntry -> mapEntry.value.physicalOwner == physicalOwner }
            ?.key
        val name = logicalOwner?.fqNameWhenAvailable?.asString()
            ?: logicalOwner?.name?.asString()
            ?: physicalOwner.fqNameWhenAvailable?.asString()
            ?: physicalOwner.name.asString()
        return name
    }

    private fun IrClass.staticInitializerOrNull(): IrSimpleFunction? =
        declarations.filterIsInstance<IrSimpleFunction>()
            .singleOrNull { it.origin == DOTNET_STATIC_INITIALIZER }

    private fun IrFile.staticInitializerOrNull(): IrSimpleFunction? =
        declarations.filterIsInstance<IrSimpleFunction>()
            .singleOrNull { it.origin == DOTNET_STATIC_INITIALIZER }
}
