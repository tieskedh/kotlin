/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.dotNetInvariantGenericOwnerConstructorUseOrNull
import org.jetbrains.kotlin.backend.dotnet.lower.dotNetProjectedGenericOwnerAllocationOrNull
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.IrErrorModuleFragment
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DotNetGenericOwnerProjectedAllocationTest {
    @Test
    fun projectedResultDoesNotReplaceTheOrderedAllocationVector() {
        val fixture = Fixture()
        val call = fixture.call()
        val allocation = assertNotNull(call.dotNetProjectedGenericOwnerAllocationOrNull())
        assertSame(fixture.owner, allocation.constructedClass)
        assertEquals(listOf(fixture.first, fixture.second), allocation.substitutions.values.toList())
        assertEquals(fixture.owner.symbol.typeWith(fixture.first, fixture.second), allocation.constructedType)
        assertNull(call.dotNetInvariantGenericOwnerConstructorUseOrNull(), "strict encoding agreement remains unchanged")

        val exact = fixture.call(fixture.owner.symbol.typeWith(fixture.first, fixture.second))
        assertNotNull(exact.dotNetInvariantGenericOwnerConstructorUseOrNull())
        assertNull(exact.dotNetProjectedGenericOwnerAllocationOrNull())
        exact.typeArguments.reverse()
        assertNull(exact.dotNetInvariantGenericOwnerConstructorUseOrNull(), "conflicting invariant encodings are not repaired")
        assertNull(exact.dotNetProjectedGenericOwnerAllocationOrNull())
    }

    @Test
    fun onlyACompleteIndependentVectorCanDescribeAProjectedAllocation() {
        val fixture = Fixture()
        assertNull(fixture.call().apply { typeArguments[0] = null }.dotNetProjectedGenericOwnerAllocationOrNull())
        assertNull(fixture.call().apply { typeArguments.removeAt(0) }.dotNetProjectedGenericOwnerAllocationOrNull())
        assertNull(fixture.call(fixture.first).dotNetProjectedGenericOwnerAllocationOrNull())
        assertNull(fixture.call(fixture.projected.makeNullable()).dotNetProjectedGenericOwnerAllocationOrNull())
        val partial = IrSimpleTypeImpl(
            fixture.owner.symbol, SimpleTypeNullability.NOT_SPECIFIED,
            listOf(IrStarProjectionImpl, makeTypeProjection(fixture.second, Variance.INVARIANT)), emptyList(),
        )
        assertNull(fixture.call(partial).dotNetProjectedGenericOwnerAllocationOrNull())
        assertNull(fixture.call().apply { typeArguments[0] = fixture.projected }.dotNetProjectedGenericOwnerAllocationOrNull())
        assertNull(fixture.call().apply {
            typeArguments[0] = fixture.owner.typeParameters[0].symbol.defaultType.makeNullable()
        }.dotNetProjectedGenericOwnerAllocationOrNull())
    }

    @Test
    fun containersRetainTheirLastAllocationButValuesCastsAndJoinsDoNot() {
        val fixture = Fixture()
        val call = fixture.call()
        val prefix = fixture.call()
        val block = IrBlockImpl(0, 0, fixture.projected).apply { statements += listOf(prefix, call) }
        val composite = IrCompositeImpl(0, 0, fixture.projected).apply { statements += block }
        assertNotNull(composite.dotNetProjectedGenericOwnerAllocationOrNull())
        assertEquals(2, block.statements.size)
        assertSame(prefix, block.statements[0], "observing the final producer must not remove effects")
        assertSame(call, block.statements[1])

        val variable = buildVariable(
            fixture.owner, 0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier("value"), fixture.projected, isVar = false,
        ).apply { initializer = call }
        val read = IrGetValueImpl(0, 0, variable.type, variable.symbol)
        assertNull(read.dotNetProjectedGenericOwnerAllocationOrNull(), "an existing star value supplies no constructor vector")
        assertNull(IrTypeOperatorCallImpl(
            0, 0, fixture.projected, IrTypeOperator.CAST, fixture.projected, read,
        ).dotNetProjectedGenericOwnerAllocationOrNull())
        assertNull(IrWhenImpl(0, 0, fixture.projected).dotNetProjectedGenericOwnerAllocationOrNull())
        block.statements += read
        assertNull(block.dotNetProjectedGenericOwnerAllocationOrNull(), "an earlier allocation does not describe a later read")
        block.statements.clear()
        assertNull(block.dotNetProjectedGenericOwnerAllocationOrNull())
    }

    private class Fixture {
        private val fragment = IrExternalPackageFragmentImpl(
            IrExternalPackageFragmentSymbolImpl(), FqName("sample"), IrErrorModuleFragment,
        )
        val owner = newClass("Container").apply {
            addTypeParameter { name = Name.identifier("Left") }
            addTypeParameter { name = Name.identifier("Right") }
        }
        val first: IrType = newClass("First").symbol.typeWith()
        val second: IrType = newClass("Second").symbol.typeWith()
        val projected = IrSimpleTypeImpl(
            owner.symbol, SimpleTypeNullability.NOT_SPECIFIED,
            listOf(IrStarProjectionImpl, IrStarProjectionImpl), emptyList(),
        )
        private val constructor = IrFactoryImpl.buildConstructor {
            returnType = owner.symbol.typeWith(owner.typeParameters.map { it.symbol.defaultType })
        }.apply { parent = owner }

        fun call(result: IrType = projected): IrConstructorCallImpl =
            IrConstructorCallImpl.fromSymbolOwner(0, 0, result, constructor.symbol).apply {
                typeArguments[0] = first
                typeArguments[1] = second
            }

        private fun newClass(value: String): IrClass = IrFactoryImpl.buildClass {
            name = Name.identifier(value)
        }.apply { parent = fragment }
    }
}
