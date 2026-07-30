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
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks the IR-only owner mapped to Kotlin.Runtime.Internal.PropertyReferenceFactory. */
internal var IrClass.isDotNetPropertyReferenceFactory: Boolean? by irAttribute(copyByDefault = false)

/**
 * IR-only declarations for the cross-assembly property-reference wrapper factories.
 *
 * The actual wrapper classes stay private to Kotlin.Runtime. Only these static factories are
 * metadata-public, because lowered user modules must construct the wrappers. Logical generic
 * arguments exist solely in IR; all factory parameters and results map to the erased runtime
 * identities described by the property-reference draft ADR.
 */
internal class DotNetPropertyReferenceSymbols(
    private val irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    private val factoryClass: IrClass = run {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        irFactory.buildClass {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("PropertyReferenceFactory")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = runtimeInternalPackage
            isDotNetPropertyReferenceFactory = true
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
        }
    }

    private val factories: Map<Pair<Int, Boolean>, IrSimpleFunction> = buildMap {
        for (arity in 0..2) {
            put(arity to false, createFactory(arity, mutable = false))
            put(arity to true, createFactory(arity, mutable = true))
        }
    }

    private val localFactories: Map<Boolean, IrSimpleFunction> = mapOf(
        false to createLocalFactory(mutable = false),
        true to createLocalFactory(mutable = true),
    )

    fun factory(arity: Int, mutable: Boolean): IrSimpleFunction =
        factories[arity to mutable]
            ?: error("Internal .NET backend error: no property-reference factory for arity $arity")

    fun localFactory(mutable: Boolean): IrSimpleFunction =
        localFactories.getValue(mutable)

    fun implementedFactories(): List<IrSimpleFunction> =
        factories.values + localFactories.values

    private fun createFactory(arity: Int, mutable: Boolean): IrSimpleFunction {
        val function = factoryClass.addFunction(
            name = "Create${if (mutable) "Mutable" else ""}Property$arity",
            returnType = irBuiltIns.anyNType,
            visibility = DescriptorVisibilities.PUBLIC,
            isStatic = true,
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB,
        )
        repeat(arity) { index ->
            function.addTypeParameter {
                name = Name.identifier("R$index")
                superTypes += irBuiltIns.anyNType
            }
        }
        function.addTypeParameter {
            name = Name.identifier("V")
            superTypes += irBuiltIns.anyNType
        }

        val typeArguments = function.typeParameters.map { it.defaultType }
        function.returnType = irBuiltIns.getKPropertyClass(mutable, arity).typeWithArguments(typeArguments)
        function.addValueParameter("name", irBuiltIns.stringType)
        function.addValueParameter(
            "getter",
            irBuiltIns.functionN(arity).symbol.typeWithArguments(typeArguments),
        )
        if (mutable) {
            function.addValueParameter(
                "setter",
                irBuiltIns.functionN(arity + 1).symbol.typeWithArguments(typeArguments + irBuiltIns.unitType),
            )
        }
        return function
    }

    private fun createLocalFactory(mutable: Boolean): IrSimpleFunction {
        val function = factoryClass.addFunction(
            name = "CreateLocalDelegated${if (mutable) "Mutable" else ""}Property0",
            returnType = irBuiltIns.anyNType,
            visibility = DescriptorVisibilities.PUBLIC,
            isStatic = true,
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB,
        )
        function.addTypeParameter {
            name = Name.identifier("V")
            superTypes += irBuiltIns.anyNType
        }
        function.returnType = irBuiltIns.getKPropertyClass(mutable, 0)
            .typeWithArguments(listOf(function.typeParameters.single().defaultType))
        function.addValueParameter("name", irBuiltIns.stringType)
        return function
    }
}
