/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.InnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesMemberBodyLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull

private var IrClass.dotNetOuterThisField: IrField? by irAttribute(copyByDefault = false)
private var IrConstructor.dotNetConstructorWithOuterThis: IrConstructor? by irAttribute(copyByDefault = false)
private var IrClass.dotNetOriginalInnerPrimaryConstructor: IrConstructor? by irAttribute(copyByDefault = false)

/**
 * The JVM/common inner-class representation, reused directly for CLR nested metadata: every inner
 * class owns a private `this$0` field, and every constructor replaces its dispatch receiver with a
 * regular leading outer-instance parameter. [InnerClassesMemberBodyLowering] then rewrites outer
 * `this` reads into field chains, while [InnerClassConstructorCallsLowering] moves the source call's
 * dispatch receiver into that leading argument.
 *
 * CLR-specific probe result (`innerprobe_s1`/`_s2`): both CoreCLR 10.0.9 and Framework 4.8 permit
 * the common lowering's `stfld this$0` before the base `.ctor` call. No CLR-specific constructor
 * reordering is needed. Generic OUTER classes remain shape-gate-rejected: unlike the JVM signature
 * model, a CLR nested type does not inherit its metadata parent's `!n` parameter space.
 */
internal class DotNetInnerClassesSupport(private val irFactory: IrFactory) : InnerClassesSupport {
    override fun getOuterThisField(innerClass: IrClass): IrField =
        innerClass::dotNetOuterThisField.getOrSetIfNull {
            check(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
            irFactory.buildField {
                name = Name.identifier("this$0")
                type = innerClass.parentAsClass.defaultType
                origin = IrDeclarationOrigin.FIELD_FOR_OUTER_THIS
                visibility = DescriptorVisibilities.PRIVATE
                isFinal = true
            }.apply {
                parent = innerClass
            }
        }

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor {
        val innerClass = innerClassConstructor.parentAsClass
        check(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
        return innerClassConstructor::dotNetConstructorWithOuterThis.getOrSetIfNull {
            createConstructorWithOuterThis(innerClassConstructor)
        }.also {
            if (innerClassConstructor.isPrimary) {
                innerClass.dotNetOriginalInnerPrimaryConstructor = innerClassConstructor
            }
        }
    }

    override fun getInnerClassOriginalPrimaryConstructorOrNull(innerClass: IrClass): IrConstructor? {
        check(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
        return innerClass.dotNetOriginalInnerPrimaryConstructor
    }

    private fun createConstructorWithOuterThis(oldConstructor: IrConstructor): IrConstructor =
        irFactory.buildConstructor {
            updateFrom(oldConstructor)
            returnType = oldConstructor.returnType
        }.apply {
            parent = oldConstructor.parent
            returnType = oldConstructor.returnType
            copyAnnotationsFrom(oldConstructor)
            copyTypeParametersFrom(oldConstructor)
            val outerThisParameter = buildValueParameter(this) {
                kind = IrParameterKind.Regular
                origin = IrDeclarationOrigin.FIELD_FOR_OUTER_THIS
                name = Name.identifier("this$0")
                type = oldConstructor.parentAsClass.parentAsClass.defaultType
            }
            parameters = listOf(outerThisParameter) + oldConstructor.nonDispatchParameters.map { it.copyTo(this) }
            metadata = oldConstructor.metadata
        }
}

internal class DotNetInnerClassesLowering(context: DotNetBackendContext) : InnerClassesLowering(context)

internal class DotNetInnerClassesMemberBodyLowering(context: DotNetBackendContext) :
    InnerClassesMemberBodyLowering(context)

internal class DotNetInnerClassConstructorCallsLowering(context: DotNetBackendContext) :
    InnerClassConstructorCallsLowering(context)
