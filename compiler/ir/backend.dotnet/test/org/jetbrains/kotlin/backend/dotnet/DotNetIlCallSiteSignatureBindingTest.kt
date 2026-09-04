/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.jetbrains.kotlin.types.Variance

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

    @Test
    fun `MethodImpl signatures bind owner parameters and keep method parameters open`() {
        val implementation = genericClass("MethodImplBody`1", 1)
        val declaration = genericClass("MethodImplDeclaration`1", 1)
        val collection = genericClass("MethodImplCollection`1", 1)
        val ownerParameter = DotNetIlValueType.TypeParameter(0, isMethodParameter = false)
        val methodParameter = DotNetIlValueType.TypeParameter(0, isMethodParameter = true)
        val methodValue = DotNetIlValueType.GenericInstance(collection, listOf(methodParameter))
        val body = DotNetIlFunctionInfo(
            implementation,
            DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Value(ownerParameter),
                parameterTypes = listOf(
                    implementation.dotNetOpenSelfType(),
                    methodValue,
                    ownerParameter,
                ),
                hasThis = true,
                methodGenericParameterCount = 1,
            ),
        )
        val slot = DotNetIlFunctionInfo(
            declaration,
            DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Value(ownerParameter),
                parameterTypes = listOf(
                    declaration.dotNetOpenSelfType(),
                    methodValue,
                    ownerParameter,
                ),
                hasThis = true,
                methodGenericParameterCount = 1,
            ),
        )
        val selectedDeclaration = DotNetIlValueType.GenericInstance(
            declaration,
            listOf(ownerParameter),
        )

        val bodyShape = body.bindEffectiveMethodImplSignatureAt(implementation.dotNetOpenSelfType())
        val declarationShape = slot.bindEffectiveMethodImplSignatureAt(selectedDeclaration)

        assertEquals(declarationShape, bodyShape)
        assertEquals(listOf(methodValue, ownerParameter), declarationShape.explicitPhysicalParameterTypes)
        assertEquals(DotNetIlReturnType.Value(ownerParameter), declarationShape.returnType)

        val incorrectlyClosedBody = DotNetIlFunctionInfo(
            implementation,
            body.signature.copy(
                parameterTypes = listOf(
                    implementation.dotNetOpenSelfType(),
                    DotNetIlValueType.GenericInstance(collection, listOf(DotNetIlValueType.String)),
                    ownerParameter,
                ),
            ),
        )
        assertNotEquals(
            declarationShape,
            incorrectlyClosedBody.bindEffectiveMethodImplSignatureAt(implementation.dotNetOpenSelfType()),
        )
    }

    @Test
    fun `MethodImpl comparison retains every physical signature coordinate`() {
        val owner = DotNetIlClassInfo("MethodImplCoordinates")
        val receiver = DotNetIlValueType.UserClass(owner)
        fun shape(
            hasThis: Boolean = true,
            methodArity: Int = 0,
            parameters: List<DotNetIlValueType> = listOf(receiver),
            result: DotNetIlReturnType = DotNetIlReturnType.Value(DotNetIlValueType.Int32),
            split: Boolean = false,
        ): DotNetIlEffectiveMethodImplSignature = DotNetIlFunctionInfo(
            owner,
            DotNetIlMethodSignature(
                returnType = result,
                parameterTypes = parameters,
                hasThis = hasThis,
                hasSplitNullableResult = split,
                methodGenericParameterCount = methodArity,
            ),
        ).bindEffectiveMethodImplSignatureAt(receiver)

        val baseline = shape(parameters = listOf(receiver, DotNetIlValueType.String, DotNetIlValueType.Int32))
        assertNotEquals(baseline, shape(hasThis = false, parameters = baseline.explicitPhysicalParameterTypes))
        assertNotEquals(baseline, shape(methodArity = 1, parameters = listOf(
            receiver,
            DotNetIlValueType.String,
            DotNetIlValueType.Int32,
        )))
        assertNotEquals(baseline, shape(parameters = listOf(
            receiver,
            DotNetIlValueType.Int32,
            DotNetIlValueType.String,
        )))
        assertNotEquals(baseline, shape(
            parameters = listOf(receiver, DotNetIlValueType.String, DotNetIlValueType.Int32),
            result = DotNetIlReturnType.Value(DotNetIlValueType.String),
        ))

        val split = shape(split = true)
        val directBoolReference = shape(parameters = listOf(
            receiver,
            DotNetIlValueType.ByReference(DotNetIlValueType.Boolean),
        ))
        assertEquals(split.explicitPhysicalParameterTypes, directBoolReference.explicitPhysicalParameterTypes)
        assertNotEquals(split, directBoolReference)
    }

    @Test
    fun `MethodImpl owner construction binds parameters positionally`() {
        val owner = genericClass("MethodImplPermutation`2", 2)
        val first = DotNetIlValueType.TypeParameter(0, isMethodParameter = false)
        val second = DotNetIlValueType.TypeParameter(1, isMethodParameter = false)
        val info = DotNetIlFunctionInfo(
            owner,
            DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Value(first),
                parameterTypes = listOf(owner.dotNetOpenSelfType(), second),
                hasThis = true,
                methodGenericParameterCount = 0,
            ),
        )

        val bound = info.bindEffectiveMethodImplSignatureAt(
            DotNetIlValueType.GenericInstance(owner, listOf(second, first)),
        )

        assertEquals(DotNetIlReturnType.Value(second), bound.returnType)
        assertEquals(listOf(first), bound.explicitPhysicalParameterTypes)
    }

    @Test
    fun `MethodImpl signature binding rejects a fabricated declaration owner`() {
        val owner = genericClass("MethodImplOwner`1", 1)
        val other = genericClass("OtherMethodImplOwner`1", 1)
        val info = DotNetIlFunctionInfo(
            owner,
            DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Void,
                parameterTypes = listOf(owner.dotNetOpenSelfType()),
                hasThis = true,
                methodGenericParameterCount = 0,
            ),
        )

        assertFailsWith<IllegalStateException> {
            info.bindEffectiveMethodImplSignatureAt(other.dotNetOpenSelfType())
        }
        assertFailsWith<IllegalStateException> {
            info.bindEffectiveMethodImplSignatureAt(DotNetIlValueType.UserClass(owner))
        }
        assertFailsWith<IllegalStateException> {
            info.bindEffectiveMethodImplSignatureAt(DotNetIlValueType.GenericInstance(owner, emptyList()))
        }
        val invalidSplit = DotNetIlFunctionInfo(
            DotNetIlClassInfo("InvalidSplitMethodImpl"),
            DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Void,
                parameterTypes = emptyList(),
                hasSplitNullableResult = true,
                methodGenericParameterCount = 0,
            ),
        )
        assertFailsWith<IllegalStateException> {
            invalidSplit.bindEffectiveMethodImplSignatureAt(DotNetIlValueType.UserClass(invalidSplit.owner))
        }
    }

    private fun genericClass(name: String, arity: Int): DotNetIlClassInfo = DotNetIlClassInfo(
        name,
        typeParameterVariances = List(arity) { Variance.INVARIANT },
    )
}
