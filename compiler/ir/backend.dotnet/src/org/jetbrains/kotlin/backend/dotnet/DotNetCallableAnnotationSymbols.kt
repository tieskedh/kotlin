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
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks the IR-only owner mapped to Kotlin.Runtime.Internal.CallableAnnotationFactory. */
internal var IrClass.isDotNetCallableAnnotationFactory: Boolean? by irAttribute(copyByDefault = false)

/**
 * Compiler/runtime ABI for turning declaration-owned annotation evidence into the one read-only
 * Kotlin List transport used by KAnnotatedElement. Kotlin values and foreign CLR metadata remain
 * separate producers; this factory owns only their common runtime transport.
 */
internal class DotNetCallableAnnotationSymbols(
    irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    private val factoryClass: IrClass
    val empty: IrSimpleFunction
    val create: IrSimpleFunction
    val foreign: IrSimpleFunction

    init {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        factoryClass = irFactory.buildClass {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("CallableAnnotationFactory")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = runtimeInternalPackage
            isDotNetCallableAnnotationFactory = true
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
        }

        val annotationListType = irBuiltIns.listClass.typeWith(irBuiltIns.annotationType)
        empty = factoryClass.addFunction(
            name = "Empty",
            returnType = annotationListType,
            visibility = DescriptorVisibilities.PUBLIC,
            isStatic = true,
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB,
        )
        create = factoryClass.addFunction(
            name = "Create",
            returnType = annotationListType,
            visibility = DescriptorVisibilities.PUBLIC,
            isStatic = true,
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB,
        ).apply {
            addValueParameter("values", irBuiltIns.arrayClass.typeWith(irBuiltIns.annotationType))
        }
        foreign = factoryClass.addFunction(
            name = "Foreign",
            returnType = annotationListType,
            visibility = DescriptorVisibilities.PUBLIC,
            isStatic = true,
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB,
        ).apply {
            addValueParameter("owner", irBuiltIns.kClassClass.starProjectedType)
            addValueParameter("metadataToken", irBuiltIns.intType)
            addValueParameter("memberKind", irBuiltIns.intType)
        }
    }

    fun implementedFunctions(): List<IrSimpleFunction> = listOf(empty, create, foreign)
}
