/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.defaultType as symbolDefaultType
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks the IR-only class mapped to Kotlin.Runtime.Internal.FunctionReferenceBase. */
internal var IrClass.isDotNetFunctionReferenceBase: Boolean? by irAttribute(copyByDefault = false)

/**
 * IR-only declarations for the Native-shaped function-reference implementation base.
 *
 * Generated direct references extend this class, while lambdas continue to extend Any. The
 * physical runtime base owns stable equality, hashing, and rendering; generated subclasses only
 * provide the bound values that participate in equality. This is a compiler/runtime
 * implementation contract, not another Kotlin callable identity or execution interface.
 */
internal class DotNetFunctionReferenceSymbols(
    irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    val baseClass: IrClass
    val constructor: IrConstructor
    val boundValueAt: IrSimpleFunction
    val getReturnType: IrSimpleFunction
    val getParameters: IrSimpleFunction?
    val getTypeParameters: IrSimpleFunction?
    val callErased: IrSimpleFunction

    init {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        val kAnnotatedElement = irBuiltIns.kCallableClass.owner.superTypes
            .mapNotNull { type -> type.classOrNull?.owner }
            .singleOrNull { owner -> owner.fqNameWhenAvailable?.asString() == "kotlin.reflect.KAnnotatedElement" }
        baseClass = irFactory.buildClass {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("FunctionReferenceBase")
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
            kind = ClassKind.CLASS
        }.apply {
            parent = runtimeInternalPackage
            isDotNetFunctionReferenceBase = true
            superTypes = listOfNotNull(irBuiltIns.anyType, kAnnotatedElement?.defaultType)
            createThisReceiverParameter()
        }
        constructor = baseClass.addConstructor {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            visibility = DescriptorVisibilities.PROTECTED
            isPrimary = true
        }.apply {
            addValueParameter("id", irBuiltIns.stringType)
            addValueParameter("arity", irBuiltIns.intType)
            addValueParameter("flags", irBuiltIns.intType)
            addValueParameter("boundValueCount", irBuiltIns.intType)
            addValueParameter("name", irBuiltIns.stringType)
            addValueParameter(
                "annotations",
                irBuiltIns.listClass.typeWith(irBuiltIns.annotationType),
            )
            // Adapted references that intentionally expose only FunctionN pass null. Every
            // KFunction reference supplies one declaration-owned signature array whose first
            // element is KType and whose remainder contains its own KTypeParameter objects.
            addValueParameter(
                "signature",
                irBuiltIns.arrayClass.typeWithArguments(listOf(irBuiltIns.anyNType)).makeNullable(),
            )
            addValueParameter(
                "parameterFactory",
                irBuiltIns.functionN(2).symbol.typeWithArguments(
                    listOf(irBuiltIns.anyNType, irBuiltIns.anyNType, irBuiltIns.anyNType),
                ).makeNullable(),
            )
        }
        if (kAnnotatedElement != null) {
            val superProperty = kAnnotatedElement.properties
                .single { property -> property.name.asString() == "annotations" }
            val superGetter = superProperty.getter
                ?: error("Internal .NET backend error: KAnnotatedElement.annotations has no getter")
            val property = baseClass.addProperty {
                origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                name = superProperty.name
                visibility = superProperty.visibility
            }.apply {
                overriddenSymbols = listOf(superProperty.symbol)
            }
            property.addGetter {
                origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                returnType = superGetter.returnType
                visibility = superGetter.visibility
                modality = Modality.FINAL
            }.apply {
                overriddenSymbols = listOf(superGetter.symbol)
                parameters += createDispatchReceiverParameterWithClassParent()
            }
        }
        boundValueAt = baseClass.addFunction {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("BoundValueAt")
            visibility = DescriptorVisibilities.PROTECTED
            modality = Modality.OPEN
            returnType = irBuiltIns.anyNType
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
            addValueParameter("index", irBuiltIns.intType)
        }
        getReturnType = baseClass.addFunction {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("GetReturnType")
            visibility = DescriptorVisibilities.PROTECTED
            modality = Modality.FINAL
            returnType = irBuiltIns.kTypeClass.symbolDefaultType
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
        }
        callErased = baseClass.addFunction {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("CallErased")
            visibility = DescriptorVisibilities.PROTECTED
            modality = Modality.FINAL
            returnType = irBuiltIns.anyNType
        }.apply {
            parameters += createDispatchReceiverParameterWithClassParent()
            addValueParameter(
                "args",
                irBuiltIns.arrayClass.typeWithArguments(listOf(irBuiltIns.anyNType)),
            )
        }
        getParameters = irBuiltIns.kCallableClass.owner.properties
            .singleOrNull { property -> property.name.asString() == "parameters" }
            ?.getter
            ?.let { parametersGetter ->
                baseClass.addFunction {
                    origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                    name = Name.identifier("GetParameters")
                    visibility = DescriptorVisibilities.PROTECTED
                    modality = Modality.FINAL
                    returnType = parametersGetter.returnType
                }.apply {
                    parameters += createDispatchReceiverParameterWithClassParent()
                }
            }
        getTypeParameters = irBuiltIns.kCallableClass.owner.properties
            .singleOrNull { property -> property.name.asString() == "typeParameters" }
            ?.getter
            ?.let { typeParametersGetter ->
                baseClass.addFunction {
                    origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                    name = Name.identifier("GetTypeParameters")
                    visibility = DescriptorVisibilities.PROTECTED
                    modality = Modality.FINAL
                    returnType = typeParametersGetter.returnType
                }.apply {
                    parameters += createDispatchReceiverParameterWithClassParent()
                }
        }
    }
}
