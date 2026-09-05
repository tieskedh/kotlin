/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/**
 * Projects the complete BOUND current MethodDef signature into the emitter's verifier-visible
 * vocabulary. Method name and flags remain owned by the emitter's IR declaration path.
 *
 * These projection functions are the only permitted bridge from symbolic local declaration
 * authority to [DotNetIlFunctionInfo]. In particular, a caller must not remap the function's
 * logical IR type when [DotNetLocalGenericOwnerPhysicalAuthority.currentMethodOrNull] selected a MethodDef: doing
 * so would make a forward reference depend on whether its MethodDef happened to be registered
 * already. Absence means that [function] has no current-MethodDef authority. Once an identity is
 * present, every incomplete or contradictory projection fails closed.
 */
internal fun DotNetLocalGenericOwnerPhysicalAuthority.currentMethodInfoOrNull(
    function: IrSimpleFunction,
    typeMapper: DotNetIlTypeMapper,
    physicalMethodName: String?,
): DotNetIlFunctionInfo? {
    val identity = currentMethodOrNull(function.symbol) ?: return null
    check(identity.function === function.symbol) {
        "Internal .NET backend error: current MethodDef authority names another emitted function"
    }
    return projectBoundLocalMethodInfo(
        function,
        identity,
        typeMapper,
        physicalMethodName,
        authorityDescription = "current MethodDef",
    )
}

/**
 * Projects the exact BOUND local MethodDef already selected for [function]'s emission instance.
 * Unlike [currentMethodInfoOrNull], this includes callable-family and complete-family endpoints
 * whose stable MethodDef identity can be anchored to another logical function. It is intended for
 * forward MethodImpl declaration resolution only; emitted body headers remain governed by the
 * deliberately narrower current-MethodDef authority.
 */
internal fun DotNetLocalGenericOwnerPhysicalAuthority.selectedBoundMethodInfoOrNull(
    function: IrSimpleFunction,
    typeMapper: DotNetIlTypeMapper,
    physicalMethodName: String?,
): DotNetIlFunctionInfo? {
    val identity = selectedBoundMethodForEmissionOrNull(function.symbol) ?: return null
    return projectBoundLocalMethodInfo(
        function,
        identity,
        typeMapper,
        physicalMethodName,
        authorityDescription = "selected MethodDef",
    )
}

/** Projects one already-Bound local symbolic carrier without remapping a logical Kotlin type. */
internal fun DotNetGenericOwnerSymbolicCarrierReference.projectBoundLocalCarrierToIlType(
    typeMapper: DotNetIlTypeMapper,
    typeOwnerIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    typeOwner: DotNetIlClassInfo,
    methodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local? = null,
    methodGenericArity: Int = 0,
    authorityDescription: String,
): DotNetIlValueType = when (this) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> when (kind) {
        DotNetGenericOwnerPhysicalTypeKind.BOOLEAN -> DotNetIlValueType.Boolean
        DotNetGenericOwnerPhysicalTypeKind.INT32 -> DotNetIlValueType.Int32
        DotNetGenericOwnerPhysicalTypeKind.STRING -> DotNetIlValueType.String
        DotNetGenericOwnerPhysicalTypeKind.OBJECT -> DotNetIlValueType.Object
        DotNetGenericOwnerPhysicalTypeKind.VOID ->
            error("Internal .NET backend error: void entered a $authorityDescription value slot")
        DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.NAMED,
        DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
        -> error("Internal .NET backend error: structural physical type kind entered a leaf")
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> when (val parameterBinder = binder) {
        is DotNetGenericOwnerPhysicalGenericBinderReference.Type -> {
            check(parameterBinder.definition == typeOwnerIdentity && index in 0 until typeOwner.typeParameterCount) {
                "Internal .NET backend error: $authorityDescription borrowed a foreign TypeDef parameter"
            }
            DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
        }
        is DotNetGenericOwnerPhysicalGenericBinderReference.Method -> {
            check(methodIdentity != null && parameterBinder.definition == methodIdentity &&
                    index in 0 until methodGenericArity) {
                "Internal .NET backend error: $authorityDescription borrowed another MethodDef parameter"
            }
            DotNetIlValueType.TypeParameter(index, isMethodParameter = true)
        }
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
        val constructionOwner = (definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local)
            ?.methodAuthorityClassInfoOrNull(typeMapper)
            ?: dotNetUnsupported(
                "BOUND $authorityDescription contains an unavailable local CLR construction",
            )
        check(constructionOwner.typeParameterCount == arguments.size && arguments.isNotEmpty()) {
            "Internal .NET backend error: BOUND $authorityDescription construction changed arity"
        }
        DotNetIlValueType.GenericInstance(
            constructionOwner,
            arguments.map { argument ->
                argument.projectBoundLocalCarrierToIlType(
                    typeMapper,
                    typeOwnerIdentity,
                    typeOwner,
                    methodIdentity,
                    methodGenericArity,
                    authorityDescription,
                )
            },
        )
    }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
        DotNetIlValueType.GenericArray(
            element.projectBoundLocalCarrierToIlType(
                typeMapper,
                typeOwnerIdentity,
                typeOwner,
                methodIdentity,
                methodGenericArity,
                authorityDescription,
            ),
        )
}

private fun DotNetLocalGenericOwnerPhysicalAuthority.projectBoundLocalMethodInfo(
    function: IrSimpleFunction,
    identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    typeMapper: DotNetIlTypeMapper,
    physicalMethodName: String?,
    authorityDescription: String,
): DotNetIlFunctionInfo {
    val declarations = checkNotNull(boundDeclarations) {
        "Internal .NET backend error: $authorityDescription authority has no BOUND declaration index"
    }
    val method = checkNotNull(declarations.methodDescriptionOrNull(identity)) {
        "Internal .NET backend error: $authorityDescription authority lost its complete MethodDef"
    }
    val ownerIdentity = method.declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: error("Internal .NET backend error: a selected local MethodDef has a non-local TypeDef owner")
    check(ownerIdentity.owner === (function.parent as? IrClass)?.symbol) {
        "Internal .NET backend error: $authorityDescription authority changed its emitted owner"
    }
    val owner = ownerIdentity.methodAuthorityClassInfoOrNull(typeMapper)
        ?: dotNetUnsupported(
            "BOUND physical owner of $authorityDescription '${function.name.asString()}' is unavailable",
        )
    check(method.genericParameters.all { parameter -> parameter.isUnconstrained }) {
        "Internal .NET backend error: the admitted selected MethodDef grammar became constrained"
    }

    val resultLayout = method.signature.resultLayout
    val returnType = when (resultLayout) {
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> DotNetIlReturnType.Void
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
            DotNetIlReturnType.Value(resultLayout.slot.carrier.projectBoundLocalCarrierToIlType(
                typeMapper,
                ownerIdentity,
                owner,
                identity,
                method.signature.genericArity,
                authorityDescription,
            ))
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            DotNetIlReturnType.Value(resultLayout.payloadSlot.carrier.projectBoundLocalCarrierToIlType(
                typeMapper,
                ownerIdentity,
                owner,
                identity,
                method.signature.genericArity,
                authorityDescription,
            ))
    }
    val signature = DotNetIlMethodSignature(
        returnType = returnType,
        parameterTypes = buildList {
            if (method.signature.isInstance) add(owner.dotNetOpenSelfType())
            method.signature.parameterSlots.mapTo(this) { slot ->
                slot.carrier.projectBoundLocalCarrierToIlType(
                    typeMapper,
                    ownerIdentity,
                    owner,
                    identity,
                    method.signature.genericArity,
                    authorityDescription,
                )
            }
        },
        hasThis = method.signature.isInstance,
        hasSplitNullableResult = resultLayout is
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable,
        methodGenericParameterCount = method.signature.genericArity,
    )
    return DotNetIlFunctionInfo(
        owner = owner,
        signature = signature,
        physicalMethodName = physicalMethodName,
        genericOwnerPhysicalMethodIdentity = identity,
    )
}

internal fun DotNetGenericOwnerPhysicalTypeDefIdentity.Local.methodAuthorityClassInfoOrNull(
    typeMapper: DotNetIlTypeMapper,
): DotNetIlClassInfo? = if (view == null) {
    typeMapper.classInfoOrNull(owner.owner)
} else {
    typeMapper.genericInterfaceInfoOrNull(owner.owner)?.classInfo(view)
}
