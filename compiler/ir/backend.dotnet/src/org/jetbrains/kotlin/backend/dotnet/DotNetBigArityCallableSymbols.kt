/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks the synthetic IR view mapped to the runtime-owned non-generic `Kotlin.FunctionN`. */
internal var IrClass.isDotNetBigArityFunctionN: Boolean? by irAttribute(copyByDefault = false)

/**
 * IR-only symbols for Common's big-arity function execution capability.
 *
 * Logical `Function23` and later classifiers remain authoritative in IR and KLIB. The CLR runtime
 * has one erased `FunctionN` capability with an `object[]` invocation slot and an explicit arity;
 * a late lowering adds that capability to the same callable object and rewrites logical invokes.
 * This mirrors the JVM FunctionN boundary without introducing a second callable identity.
 */
internal class DotNetBigArityCallableSymbols(
    irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    val irClass: IrClass
    val invoke: IrSimpleFunction
    val arityGetter: IrSimpleFunction

    init {
        val runtimePackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        irClass = irFactory.buildClass {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("FunctionN")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
            kind = ClassKind.INTERFACE
        }.apply {
            parent = runtimePackage
            isDotNetBigArityFunctionN = true
            superTypes = listOf(irBuiltIns.functionClass.typeWith(irBuiltIns.anyNType))
            createThisReceiverParameter()
        }
        invoke = irClass.addFunction {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("Invoke")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
            returnType = irBuiltIns.anyNType
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
            addValueParameter(
                "args",
                irBuiltIns.arrayClass.typeWithArguments(listOf(irBuiltIns.anyNType)),
            )
        }
        val arityProperty = irClass.addProperty {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("arity")
            visibility = DescriptorVisibilities.PUBLIC
        }
        arityGetter = arityProperty.addGetter {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            returnType = irBuiltIns.intType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
        }
    }
}
