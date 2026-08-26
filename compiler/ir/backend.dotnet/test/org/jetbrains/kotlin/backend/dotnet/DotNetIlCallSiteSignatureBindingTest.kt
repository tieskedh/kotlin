/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DotNetIlCallSiteSignatureBindingTest {
    @Test
    fun `owner and method arguments bind independently without closing the declaration`() {
        val ownerParameter = DotNetIlValueType.TypeParameter(0, isMethodParameter = false)
        val methodParameter = DotNetIlValueType.TypeParameter(0, isMethodParameter = true)
        val signature = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Value(ownerParameter),
            parameterTypes = listOf(DotNetIlValueType.Object, methodParameter),
            hasThis = true,
            methodGenericParameterCount = 1,
        )

        val bound = signature.bindCallSite(
            declaredOwnerArity = 1,
            ownerArguments = listOf(DotNetIlValueType.Int32),
            methodSpecArguments = listOf(DotNetIlValueType.String),
        )

        assertEquals(listOf(DotNetIlValueType.String), bound.methodSpecArguments)
        assertEquals(
            listOf(DotNetIlValueType.Object, DotNetIlValueType.String),
            bound.verifierParameterTypes,
        )
        assertEquals(
            DotNetIlReturnType.Value(DotNetIlValueType.Int32),
            bound.verifierReturnType,
        )
        assertEquals(listOf(DotNetIlValueType.Object, methodParameter), signature.parameterTypes)
        assertEquals(DotNetIlReturnType.Value(ownerParameter), signature.returnType)
    }

    @Test
    fun `an open caller parameter is a legal MethodSpec argument`() {
        val calleeParameter = DotNetIlValueType.TypeParameter(0, isMethodParameter = true)
        val callerParameter = DotNetIlValueType.TypeParameter(1, isMethodParameter = true)
        val signature = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Value(calleeParameter),
            parameterTypes = listOf(calleeParameter),
            methodGenericParameterCount = 1,
        )

        val bound = signature.bindCallSite(
            declaredOwnerArity = 0,
            ownerArguments = emptyList(),
            methodSpecArguments = listOf(callerParameter),
        )

        assertEquals(listOf(callerParameter), bound.methodSpecArguments)
        assertEquals(listOf(callerParameter), bound.verifierParameterTypes)
        assertEquals(DotNetIlReturnType.Value(callerParameter), bound.verifierReturnType)
        assertEquals(listOf(calleeParameter), signature.parameterTypes)
    }

    @Test
    fun `MethodSpec vector never defines selected MethodDef arity`() {
        fun signature(arity: Int) = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Void,
            parameterTypes = emptyList(),
            methodGenericParameterCount = arity,
        )

        for (arity in listOf(0, 2)) {
            assertFailsWith<IllegalStateException> {
                signature(arity).bindCallSite(
                    declaredOwnerArity = 0,
                    ownerArguments = emptyList(),
                    methodSpecArguments = listOf(DotNetIlValueType.Int32),
                )
            }
        }
        assertEquals(
            listOf(DotNetIlValueType.Int32),
            signature(1).bindCallSite(
                declaredOwnerArity = 0,
                ownerArguments = emptyList(),
                methodSpecArguments = listOf(DotNetIlValueType.Int32),
            ).methodSpecArguments,
        )
    }

    @Test
    fun `direct call rendering cannot bypass selected MethodDef arity`() {
        val info = DotNetIlFunctionInfo(
            owner = DotNetIlClassInfo("MethodSpecRendererProbe"),
            signature = DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Void,
                parameterTypes = emptyList(),
                // An unused GenericParam still belongs to the physical MethodDef.
                methodGenericParameterCount = 1,
            ),
        )

        assertFailsWith<IllegalStateException> {
            info.renderCallInstruction("invoke")
        }
        assertFailsWith<IllegalStateException> {
            info.renderCallInstruction(
                "invoke",
                methodInstantiation = listOf(DotNetIlValueType.Int32, DotNetIlValueType.String),
            )
        }
        check("<int32>" in info.renderCallInstruction(
            "invoke",
            methodInstantiation = listOf(DotNetIlValueType.Int32),
        ))
    }

    @Test
    fun `arity and binder range mismatches fail closed`() {
        val signature = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Void,
            parameterTypes = listOf(
                DotNetIlValueType.ByReference(
                    DotNetIlValueType.GenericArray(
                        DotNetIlValueType.TypeParameter(1, isMethodParameter = true),
                    ),
                ),
            ),
            methodGenericParameterCount = 1,
        )

        assertFailsWith<IllegalStateException> {
            signature.bindCallSite(
                declaredOwnerArity = 1,
                ownerArguments = emptyList(),
                methodSpecArguments = listOf(DotNetIlValueType.Int32),
            )
        }
        assertFailsWith<IllegalStateException> {
            signature.bindCallSite(
                declaredOwnerArity = 0,
                ownerArguments = emptyList(),
                methodSpecArguments = emptyList(),
            )
        }
        assertFailsWith<IllegalStateException> {
            signature.bindCallSite(
                declaredOwnerArity = 0,
                ownerArguments = emptyList(),
                methodSpecArguments = listOf(DotNetIlValueType.Int32),
            )
        }
    }
}
