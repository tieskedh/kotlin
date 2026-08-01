/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.lower.InventNamesForLocalClasses
import org.jetbrains.kotlin.backend.common.lower.InventNamesForLocalFunctions
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationPopupLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.lower.VisibilityPolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrRawFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

internal var IrElement.dotNetInventedLocalClassName: String? by irAttribute(copyByDefault = false)
internal var IrClass.dotNetLocalCaptureRejectionReason: String? by irAttribute(copyByDefault = false)
internal var IrSimpleFunction.dotNetLocalCaptureRejectionReason: String? by irAttribute(copyByDefault = false)

/**
 * Gives every source local class a deterministic CLR identity before closure conversion moves it.
 * Top-level-function locals need an explicit file-facade prefix because this backend has no IR
 * file-class lowering. A per-base-name counter distinguishes only real collisions (for example,
 * same-named locals in overloads), so unrelated source movement does not churn metadata names.
 * The emitter still disambiguates against every registered user metadata name defensively.
 */
internal class DotNetInventNamesForLocalClasses(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : InventNamesForLocalClasses() {
    private val nextSuffixByBaseName = mutableMapOf<String, Int>()

    override fun computeTopLevelClassName(clazz: IrClass): String =
        clazz.fqNameWhenAvailable?.asString()
            ?: error("Top-level class has no FqName: ${clazz.name}")

    override fun sanitizeNameIfNeeded(name: String): String = name

    override fun putLocalClassName(declaration: IrElement, localClassName: String) {
        if (declaration.dotNetInventedLocalClassName != null) return
        val anchor = when (declaration) {
            is IrClass -> declaration
            is IrRichFunctionReference -> declaration.invokeFunction
            is IrFunctionExpression -> declaration.function
            else -> return
        }

        val hasNonLocalClassAncestor = generateSequence(anchor.parent as? IrDeclaration) { parent ->
            parent.parent as? IrDeclaration
        }.any { parent ->
            parent is IrClass && !parent.isAnonymousObject && parent.visibility != DescriptorVisibilities.LOCAL
        }
        val qualifiedName = if (hasNonLocalClassAncestor) {
            localClassName
        } else {
            val file = anchor.file
            val fileName = file.fileEntry.name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            val facadeBaseName = if (file.packageFqName.isRoot) {
                "${fileName}Kt"
            } else {
                "${file.packageFqName.asString()}.${fileName}Kt"
            }
            "$facadeBaseName\$$localClassName"
        }
        val suffix = nextSuffixByBaseName.getOrPut(qualifiedName) { 0 }
        nextSuffixByBaseName[qualifiedName] = suffix + 1
        declaration.dotNetInventedLocalClassName =
            if (suffix == 0) qualifiedName else "$qualifiedName\$$suffix"
    }
}

/** Gives lifted explicit local functions stable, container-wise unique metadata names. */
internal class DotNetInventNamesForLocalFunctions(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : InventNamesForLocalFunctions() {
    override val suggestUniqueNames: Boolean get() = true
    override val compatibilityModeForInlinedLocalDelegatedPropertyAccessors: Boolean get() = false

    override fun sanitizeNameIfNeeded(name: String): String = name
}

/**
 * Invokes common closure conversion for named classes, anonymous objects, explicit local
 * functions, and callable-object classes produced by [DotNetCallableReferenceLowering]. Immutable
 * captures become private fields/constructor parameters; mutable locals have already become
 * immutable references to shared cells. Inline-lambda captures are ordinary FunctionN values by
 * this point: FIR has enforced crossinline/noinline control-flow rules and the shared inliner has
 * copied every selected call site before this physical closure conversion runs.
 */
internal class DotNetLocalDeclarationsLowering private constructor(
    override val context: DotNetBackendContext,
    private val capturedParameters: MutableMap<IrValueParameter, IrValueSymbol>,
) : LocalDeclarationsLowering(
    context,
    visibilityPolicy = DotNetLocalClassVisibilityPolicy,
    newParameterToCaptured = capturedParameters,
) {
    constructor(context: DotNetBackendContext) : this(context, mutableMapOf())

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (!irBody.isEligibleLocalDeclarationBody()) return
        val existingCapturedParameters = capturedParameters.keys.toHashSet()
        super.lower(irBody, container)
        for ([parameter, captured] in capturedParameters) {
            if (parameter in existingCapturedParameters) continue
            val reason = when (val capturedDeclaration = captured.owner) {
                is IrVariable -> if (capturedDeclaration.isVar) {
                    "captures mutable local '${capturedDeclaration.name.asString()}' without shared-variable lowering"
                } else null
                is IrValueParameter -> null
                else -> null
            }
            if (reason != null) {
                when (val parent = parameter.parent) {
                    is IrConstructor -> {
                        val localClass = parent.parentAsClass
                        if (localClass.dotNetLocalCaptureRejectionReason == null) {
                            localClass.dotNetLocalCaptureRejectionReason = reason
                        }
                    }
                    is IrSimpleFunction -> if (parent.dotNetLocalCaptureRejectionReason == null) {
                        parent.dotNetLocalCaptureRejectionReason = reason
                    }
                }
            }
        }
    }

    private fun IrBody.isEligibleLocalDeclarationBody(): Boolean {
        var hasSupportedLocalDeclaration = false
        var hasCallableObjectShape = false
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.visibility == DescriptorVisibilities.LOCAL) {
                    hasSupportedLocalDeclaration = true
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.visibility == DescriptorVisibilities.LOCAL) {
                    if (
                        declaration.origin == IrDeclarationOrigin.LOCAL_FUNCTION ||
                        declaration.origin == IrDeclarationOrigin.DELEGATED_PROPERTY_ACCESSOR
                    ) {
                        hasSupportedLocalDeclaration = true
                    } else {
                        hasCallableObjectShape = true
                    }
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitFunctionReference(expression: IrFunctionReference) {
                hasCallableObjectShape = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitRawFunctionReference(expression: IrRawFunctionReference) {
                hasCallableObjectShape = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                hasCallableObjectShape = true
                expression.acceptChildrenVoid(this)
            }
        })
        return hasSupportedLocalDeclaration && !hasCallableObjectShape
    }
}

/** Pops up only declarations actually transformed by [DotNetLocalDeclarationsLowering]. */
internal class DotNetLocalDeclarationPopupLowering(context: DotNetBackendContext) :
    LocalDeclarationPopupLowering(context) {
    override fun shouldPopUp(declaration: IrDeclaration, currentScope: ScopeWithIr?): Boolean =
        declaration.isOriginallyLocalDeclaration
}

private object DotNetLocalClassVisibilityPolicy : VisibilityPolicy {
    override fun forClass(declaration: IrClass, inPublicInlineScope: Boolean): DescriptorVisibility =
        DescriptorVisibilities.PRIVATE

    // The local type itself is private/notpublic. A public metadata constructor is needed for a
    // containing facade or enclosing class to instantiate it (localprobe_s1/s2) and exposes no
    // additional Kotlin source surface.
    override fun forConstructor(declaration: IrConstructor, inInlineFunctionScope: Boolean): DescriptorVisibility =
        DescriptorVisibilities.PUBLIC

    override fun forCapturedField(value: IrValueSymbol): DescriptorVisibility = DescriptorVisibilities.PRIVATE

    override fun forSimpleFunction(declaration: IrSimpleFunction): DescriptorVisibility = DescriptorVisibilities.PRIVATE
}
