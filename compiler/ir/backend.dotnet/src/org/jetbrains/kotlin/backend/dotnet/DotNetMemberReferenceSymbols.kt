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
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks the IR-only owner mapped to Kotlin.Runtime.Internal.MemberReferenceFactory. */
internal var IrClass.isDotNetMemberReferenceFactory: Boolean? by irAttribute(copyByDefault = false)

/**
 * IR view of the sole cross-assembly entry used by compact `KClass.members` producers.
 *
 * The Runtime selects an arity-correct shared carrier. The producer supplies one ordinary
 * Function3 dispatcher for the whole reflected class plus declaration-owned Kotlin metadata.
 */
internal class DotNetMemberReferenceSymbols(
    irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    private val factoryClass: IrClass
    val createFunction: IrSimpleFunction

    init {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        factoryClass = irFactory.buildClass {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("MemberReferenceFactory")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = runtimeInternalPackage
            isDotNetMemberReferenceFactory = true
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
        }

        val anyArray = irBuiltIns.arrayClass.typeWithArguments(listOf(irBuiltIns.anyNType))
        val dispatcherType = irBuiltIns.functionN(3).symbol.typeWithArguments(
            listOf(
                irBuiltIns.intType,
                anyArray,
                irBuiltIns.intArray.owner.defaultType.makeNullable(),
                irBuiltIns.anyNType,
            )
        )
        createFunction = factoryClass.addFunction(
            name = "CreateFunction",
            returnType = irBuiltIns.anyNType,
            visibility = DescriptorVisibilities.PUBLIC,
            isStatic = true,
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB,
        ).apply {
            addValueParameter("dispatcher", dispatcherType)
            addValueParameter("memberIndex", irBuiltIns.intType)
            addValueParameter("id", irBuiltIns.stringType)
            addValueParameter("arity", irBuiltIns.intType)
            addValueParameter("flags", irBuiltIns.intType)
            addValueParameter("name", irBuiltIns.stringType)
            addValueParameter(
                "annotations",
                irBuiltIns.listClass.typeWithArguments(listOf(irBuiltIns.annotationType)),
            )
            addValueParameter("signature", anyArray.makeNullable())
            addValueParameter(
                "parameterFactory",
                irBuiltIns.functionN(2).symbol.typeWithArguments(
                    listOf(irBuiltIns.anyNType, irBuiltIns.anyNType, irBuiltIns.anyNType),
                ).makeNullable(),
            )
            addValueParameter("emptyVarargs", anyArray.makeNullable())
        }
    }

    fun implementedFunctions(): List<IrSimpleFunction> = listOf(createFunction)
}
