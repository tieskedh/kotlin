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
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isEnumClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/** Redirects the post-inlining enum intrinsics to the concrete enum's existing synthetic API. */
internal class DotNetEnumUsageLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        irModule.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val kind = expression.symbol.owner.fqNameWhenAvailable?.asString().toEnumIntrinsicKindOrNull()
                    ?: return expression
                val enumType = expression.typeArguments.singleOrNull()
                    ?: dotNetUnsupported("reified enum intrinsic '${kind.displayName}' has no single type argument")
                val enumClass = (enumType.classifierOrNull as? IrClassSymbol)?.owner
                    ?: dotNetUnsupported(
                        "reified enum intrinsic '${kind.displayName}' retained an unsubstituted type argument"
                    )
                if (!enumClass.isEnumClass) {
                    dotNetUnsupported(
                        "reified enum intrinsic '${kind.displayName}' has non-enum type " +
                                "'${enumClass.fqNameWhenAvailable}'"
                    )
                }

                val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression)
                return when (kind) {
                    EnumIntrinsicKind.VALUES -> builder.irCall(enumClass.staticFunction("values"))
                    EnumIntrinsicKind.VALUE_OF -> builder.irCall(enumClass.staticFunction("valueOf")).apply {
                        arguments[0] = expression.arguments.single()
                    }
                    EnumIntrinsicKind.ENTRIES -> {
                        val getter = enumClass.properties.singleOrNull { property ->
                            property.name.asString() == "entries" &&
                                    property.getter?.dispatchReceiverParameter == null
                        }?.getter ?: dotNetUnsupported(
                            "enum '${enumClass.fqNameWhenAvailable}' has no static entries getter"
                        )
                        builder.irCall(getter)
                    }
                }
            }
        })
    }

    private fun IrClass.staticFunction(name: String) = simpleFunctions().singleOrNull { function ->
        function.name == Name.identifier(name) && function.dispatchReceiverParameter == null
    } ?: dotNetUnsupported("enum '$fqNameWhenAvailable' has no static $name function")

    private enum class EnumIntrinsicKind(val displayName: String) {
        VALUES("enumValues"),
        VALUE_OF("enumValueOf"),
        ENTRIES("enumEntries"),
    }

    private fun String?.toEnumIntrinsicKindOrNull(): EnumIntrinsicKind? = when (this) {
        "kotlin.dotNetEnumValuesIntrinsic" -> EnumIntrinsicKind.VALUES
        "kotlin.dotNetEnumValueOfIntrinsic" -> EnumIntrinsicKind.VALUE_OF
        "kotlin.enums.enumEntriesIntrinsic" -> EnumIntrinsicKind.ENTRIES
        else -> null
    }
}
