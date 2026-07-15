/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThenReturnFalse
import org.jetbrains.kotlin.ir.builders.irIfThenReturnTrue
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irNotIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnTrue
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFinalClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions

internal val DOTNET_GENERIC_DATA_CLASS_COMPONENT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_DATA_CLASS_COMPONENT_BRIDGE")

private val DOTNET_GENERIC_DATA_CLASS_ERASED_VIEW: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_DATA_CLASS_ERASED_VIEW")

/**
 * Preserves Kotlin's erased class-identity rule for generated equality on a reified CLR generic
 * data class. JVM/JS/Native can test `other is C<*>` through their erased runtime class identity;
 * CLR `isinst C<T>` would instead reject another legal construction of the same Kotlin class.
 *
 * Each affected class receives one private, non-generic nested interface. One private explicit
 * implementation per primary-constructor property exposes its value as `object`; the generated
 * `Equals(object)` tests that unique interface and compares those erased components through the
 * established Kotlin object-equality boundary. Ordinary class storage, signatures, construction,
 * and every non-equality use remain the real reified `C<T>` representation.
 *
 * The explicit bridge methods require a CLR `.override` directive.
 * [org.jetbrains.kotlin.backend.dotnet.DotNetIlMethodCodegen] recognizes
 * [DOTNET_GENERIC_DATA_CLASS_COMPONENT_BRIDGE], keeps each bridge private, and emits that
 * MethodImpl entry; no bridge appears in the class's public reflection surface.
 */
internal class DotNetGenericDataClassLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val genericDataClasses = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isData && declaration.typeParameters.isNotEmpty()) {
                    genericDataClasses += declaration
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        genericDataClasses.forEach(::lowerGenericDataClass)
    }

    private fun lowerGenericDataClass(irClass: IrClass) {
        val generatedEquals = irClass.functions.singleOrNull { function ->
            function.name == OperatorNameConventions.EQUALS &&
                    function.origin == IrDeclarationOrigin.GENERATED_DATA_CLASS_MEMBER
        } ?: return
        val primaryConstructor = irClass.primaryConstructor
            ?: error("Internal .NET backend error: data class '${irClass.name}' has no primary constructor")
        val properties = primaryConstructor.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .map { parameter ->
                irClass.properties.single { property ->
                    property.name == parameter.name
                }
            }

        val erasedView = createErasedView(irClass)
        val viewMethods = properties.mapIndexed { index, property ->
            createViewMethod(erasedView, index).also { viewMethod ->
                createComponentBridge(irClass, property, viewMethod)
            }
        }
        irClass.declarations.add(0, erasedView)
        irClass.superTypes += erasedView.defaultType
        rewriteGeneratedEquals(generatedEquals, properties, erasedView, viewMethods)
    }

    private fun createErasedView(irClass: IrClass): IrClass = context.irFactory.buildClass {
        startOffset = irClass.startOffset
        endOffset = irClass.endOffset
        origin = DOTNET_GENERIC_DATA_CLASS_ERASED_VIEW
        name = Name.special("<DataClassErasedView>")
        visibility = DescriptorVisibilities.PRIVATE
        modality = Modality.ABSTRACT
        kind = ClassKind.INTERFACE
    }.apply {
        parent = irClass
        superTypes = listOf(context.irBuiltIns.anyType)
        createThisReceiverParameter()
    }

    private fun createViewMethod(erasedView: IrClass, index: Int): IrSimpleFunction = erasedView.addFunction {
        startOffset = erasedView.startOffset
        endOffset = erasedView.endOffset
        origin = DOTNET_GENERIC_DATA_CLASS_ERASED_VIEW
        name = componentName(index)
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.ABSTRACT
        returnType = context.irBuiltIns.anyNType
    }.apply {
        parameters += createDispatchReceiverParameterWithClassParent()
    }

    private fun createComponentBridge(
        irClass: IrClass,
        property: IrProperty,
        viewMethod: IrSimpleFunction,
    ) {
        irClass.addFunction {
            startOffset = property.startOffset
            endOffset = property.endOffset
            origin = DOTNET_GENERIC_DATA_CLASS_COMPONENT_BRIDGE
            name = viewMethod.name
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply {
            overriddenSymbols = listOf(viewMethod.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                val value = readProperty(irGet(parameters[0]), property, irClass)
                +irReturn(irImplicitCast(value, context.irBuiltIns.anyNType))
            }
        }
    }

    private fun rewriteGeneratedEquals(
        equalsFunction: IrSimpleFunction,
        properties: List<IrProperty>,
        erasedView: IrClass,
        viewMethods: List<IrSimpleFunction>,
    ) {
        equalsFunction.body = context.createIrBuilder(equalsFunction.symbol).irBlockBody {
            fun irThis() = irGet(equalsFunction.parameters[0])
            fun irOther() = irGet(equalsFunction.parameters[1])

            +irIfThenReturnTrue(irEqeqeq(irThis(), irOther()))
            +irIfThenReturnFalse(irNotIs(irOther(), erasedView.defaultType))
            val otherView = irTemporary(
                irImplicitCast(irOther(), erasedView.defaultType),
                "other_erased_view",
            )
            for ([property, viewMethod] in properties.zip(viewMethods)) {
                val thisValue = irImplicitCast(
                    readProperty(irThis(), property, equalsFunction.parent as IrClass),
                    context.irBuiltIns.anyNType,
                )
                val otherValue = irCall(viewMethod).apply {
                    arguments[0] = irGet(otherView)
                }
                +irIfThenReturnFalse(irNot(irEquals(thisValue, otherValue)))
            }
            +irReturnTrue()
        }
    }

    private fun org.jetbrains.kotlin.ir.builders.IrBuilderWithScope.readProperty(
        receiver: IrExpression,
        property: IrProperty,
        irClass: IrClass,
    ): IrExpression {
        val backingField = property.backingField
        return if (irClass.isFinalClass && backingField != null) {
            irGetField(receiver, backingField)
        } else {
            val getter = property.getter
                ?: error("Internal .NET backend error: data property '${property.name}' has no getter")
            irCall(getter).apply { arguments[0] = receiver }
        }
    }

    private fun componentName(index: Int): Name = Name.special("<DataClassComponent$index>")
}
