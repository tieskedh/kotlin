/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotNetGenericInterfaceCarrierPolicyTest {
    @Test
    fun covariantReferenceArgumentsRetainTheBottomViewHazard() {
        val fixture = Fixture()
        for (argument in listOf(fixture.reference, fixture.reference.makeNullable())) {
            assertTrue(fixture.view(argument).requiresCarrier(Variance.OUT_VARIANCE, COVARIANT))
        }
    }

    @Test
    fun referenceContravarianceRequiresMatchingPhysicalVariance() {
        val fixture = Fixture()
        for (argument in listOf(fixture.reference, fixture.reference.makeNullable())) {
            val view = fixture.view(argument)
            assertFalse(view.requiresCarrier(Variance.IN_VARIANCE, CONTRAVARIANT))
            assertTrue(view.requiresCarrier(Variance.IN_VARIANCE, DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT))
        }
    }

    @Test
    fun primitiveAndOpenVariantArgumentsRemainSemantic() {
        val fixture = Fixture()
        for (argument in listOf(
            fixture.primitive, fixture.primitive.makeNullable(),
            fixture.parameter, fixture.parameter.makeNullable(),
        )) {
            val view = fixture.view(argument)
            assertTrue(view.requiresCarrier(Variance.IN_VARIANCE, CONTRAVARIANT))
            assertTrue(view.requiresCarrier(Variance.OUT_VARIANCE, COVARIANT))
        }
    }

    @Test
    fun invariantArgumentsKeepTheExistingNullableParameterBoundary() {
        val fixture = Fixture()
        val physicalVariance = DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        for (argument in listOf(
            fixture.reference, fixture.reference.makeNullable(),
            fixture.primitive, fixture.primitive.makeNullable(), fixture.parameter,
        )) {
            assertFalse(fixture.view(argument).requiresCarrier(Variance.INVARIANT, physicalVariance))
        }
        assertTrue(fixture.view(fixture.parameter.makeNullable()).requiresCarrier(Variance.INVARIANT, physicalVariance))
    }

    @Test
    fun missingOrInconsistentArityFailsClosed() {
        val fixture = Fixture()
        val view = fixture.view(fixture.reference)
        assertTrue(fixture.view(emptyList()).requiresDotNetSemanticInterfaceCarrier(
            listOf(Variance.IN_VARIANCE), listOf(CONTRAVARIANT),
        ))
        assertTrue(view.requiresDotNetSemanticInterfaceCarrier(emptyList(), listOf(CONTRAVARIANT)))
        assertTrue(view.requiresDotNetSemanticInterfaceCarrier(listOf(Variance.IN_VARIANCE), emptyList()))
        assertTrue(view.requiresDotNetSemanticInterfaceCarrier(
            listOf(Variance.IN_VARIANCE), listOf(CONTRAVARIANT, CONTRAVARIANT),
        ))
    }

    @Test
    fun starsAndUseSiteProjectionsRemainSemantic() {
        val fixture = Fixture()
        val physicalVariance = DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        assertTrue(fixture.view(listOf(IrStarProjectionImpl)).requiresCarrier(Variance.INVARIANT, physicalVariance))
        for (projection in listOf(Variance.IN_VARIANCE, Variance.OUT_VARIANCE)) {
            assertTrue(fixture.view(fixture.reference, projection).requiresCarrier(Variance.INVARIANT, physicalVariance))
        }
    }

    private fun IrSimpleType.requiresCarrier(
        declaredVariance: Variance,
        physicalVariance: DotNetGenericOwnerPhysicalTypeParameterVariance,
    ): Boolean = requiresDotNetSemanticInterfaceCarrier(listOf(declaredVariance), listOf(physicalVariance))

    private class Fixture {
        private val fragment = IrExternalPackageFragmentImpl(
            IrExternalPackageFragmentSymbolImpl(), FqName("sample"), IrErrorModuleFragment,
        )
        private val kotlinFragment = IrExternalPackageFragmentImpl(
            IrExternalPackageFragmentSymbolImpl(), FqName("kotlin"), IrErrorModuleFragment,
        )
        private val owner = newClass("Carrier").apply {
            addTypeParameter { name = Name.identifier("T") }
        }
        val reference: IrType = newClass("Reference").symbol.typeWith()
        val primitive: IrType = newClass("Int").apply { parent = kotlinFragment }.symbol.typeWith()
        val parameter: IrType = owner.typeParameters.single().symbol.defaultType

        fun view(argument: IrType, projection: Variance = Variance.INVARIANT): IrSimpleType =
            view(listOf(makeTypeProjection(argument, projection)))

        fun view(arguments: List<IrTypeArgument>): IrSimpleType = IrSimpleTypeImpl(
            owner.symbol, SimpleTypeNullability.NOT_SPECIFIED, arguments, emptyList(),
        )

        private fun newClass(value: String): IrClass = IrFactoryImpl.buildClass {
            name = Name.identifier(value)
        }.apply { parent = fragment }
    }
}
