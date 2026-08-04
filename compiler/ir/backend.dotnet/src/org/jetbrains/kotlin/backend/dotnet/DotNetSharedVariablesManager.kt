/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.Name

/** Marks the synthetic IR classifier mapped to the runtime's compiler-internal mutable cell. */
internal var IrClass.isDotNetMutableRefStub: Boolean? by irAttribute(copyByDefault = false)

/**
 * Rewrites a captured mutable local to one logical `MutableRef<T>` object, following the JVM
 * `SharedVariablesLowering` model. The physical compiler-owned cell is non-generic and stores
 * `element: object`; IR retains `T`, so reads narrow/unbox only at their logical use site.
 *
 * The synthetic class below is an IR symbol table stub only. It is never emitted into the user
 * assembly; [DotNetRuntimeTypes] maps it to
 * `[Kotlin.Runtime]Kotlin.Runtime.Internal.MutableRef`. A cell may become a private field of a
 * generated callable class, but it is not a callable representation and does not change that
 * object's erased `Kotlin.FunctionN` identity.
 */
internal class DotNetSharedVariablesManager(
    private val irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
) : SharedVariablesManager() {
    private val mutableRefClass = irFactory.buildClass {
        origin = IrDeclarationOrigin.IR_BUILTINS_STUB
        name = Name.identifier("MutableRef")
        visibility = DescriptorVisibilities.PUBLIC
    }.apply {
        parent = irBuiltIns.anyClass.owner.parent
        isDotNetMutableRefStub = true
        superTypes = listOf(irBuiltIns.anyType)
        addTypeParameter {
            name = Name.identifier("T")
            superTypes += irBuiltIns.anyNType
        }
        createThisReceiverParameter()
    }

    private val mutableRefConstructor = mutableRefClass.addConstructor {
        origin = IrDeclarationOrigin.IR_BUILTINS_STUB
        visibility = DescriptorVisibilities.PUBLIC
    }

    private val elementField = mutableRefClass.addField {
        origin = IrDeclarationOrigin.IR_BUILTINS_STUB
        name = Name.identifier("element")
        type = mutableRefClass.typeParameters.single().defaultType
        visibility = DescriptorVisibilities.PUBLIC
    }

    override fun declareSharedVariable(originalDeclaration: IrVariable): IrVariable {
        val valueType = originalDeclaration.type
        val refType = mutableRefClass.typeWith(valueType)
        val constructorCall = IrConstructorCallImpl.fromSymbolOwner(
            originalDeclaration.startOffset,
            originalDeclaration.startOffset,
            refType,
            mutableRefConstructor.symbol,
        ).apply {
            typeArguments[0] = valueType
        }
        return with(originalDeclaration) {
            IrVariableImpl(
                startOffset,
                endOffset,
                origin,
                IrVariableSymbolImpl(),
                name,
                refType,
                isVar = false,
                isConst = false,
                isLateinit = false,
            ).apply {
                initializer = constructorCall
            }
        }
    }

    override fun defineSharedValue(
        originalDeclaration: IrVariable,
        sharedVariableDeclaration: IrVariable,
    ): IrStatement {
        val initializer = originalDeclaration.initializer ?: return sharedVariableDeclaration
        val receiver = IrGetValueImpl(
            originalDeclaration.startOffset,
            originalDeclaration.endOffset,
            sharedVariableDeclaration.symbol,
        )
        val initialization = IrSetFieldImpl(
            originalDeclaration.startOffset,
            originalDeclaration.endOffset,
            elementField.symbol,
            receiver,
            initializer,
            irBuiltIns.unitType,
            origin = null,
        )
        return IrCompositeImpl(
            originalDeclaration.startOffset,
            originalDeclaration.endOffset,
            irBuiltIns.unitType,
            origin = null,
            statements = listOf(sharedVariableDeclaration, initialization),
        )
    }

    override fun getSharedValue(
        sharedVariableSymbol: IrValueSymbol,
        originalGet: IrGetValue,
    ): IrExpression = with(originalGet) {
        IrGetFieldImpl(
            startOffset,
            endOffset,
            elementField.symbol,
            type,
            IrGetValueImpl(startOffset, endOffset, sharedVariableSymbol),
            origin,
        )
    }

    override fun setSharedValue(
        sharedVariableSymbol: IrValueSymbol,
        originalSet: IrSetValue,
    ): IrExpression = with(originalSet) {
        IrSetFieldImpl(
            startOffset,
            endOffset,
            elementField.symbol,
            IrGetValueImpl(startOffset, endOffset, sharedVariableSymbol),
            value,
            type,
            origin,
        )
    }
}
