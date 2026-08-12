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
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks the IR-only view of `Kotlin.Runtime.Internal.FunctionAdapter`. */
internal var IrClass.isDotNetFunctionAdapter: Boolean? by irAttribute(copyByDefault = false)

/**
 * Compiler symbols for Common SAM-wrapper equality.
 *
 * The interface is emitted once by Kotlin.Runtime, not by a user module. It must be metadata-public
 * because Common-generated wrappers in arbitrary assemblies implement it, while its reserved
 * namespace and compiler-ABI attributes keep it outside the supported Kotlin and C# source API.
 */
internal class DotNetFunctionAdapterSymbols(
    irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    val irClass: IrClass
    val getFunctionDelegate: IrSimpleFunction

    init {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        irClass = irFactory.buildClass {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("FunctionAdapter")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
            kind = ClassKind.INTERFACE
        }.apply {
            parent = runtimeInternalPackage
            isDotNetFunctionAdapter = true
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
        }
        getFunctionDelegate = irClass.addFunction {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("getFunctionDelegate")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
            returnType = irBuiltIns.functionClass.typeWith(irBuiltIns.anyNType)
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
        }
    }
}
