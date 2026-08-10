/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.dotnet

import org.jetbrains.kotlin.backend.common.IrSpecialAnnotationsProvider
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.EnhancedNullability
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.FlexibleArrayElementVariance
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.FlexibleMutability
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.FlexibleNullability
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.RawTypeAnnotation
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance

/** Preserves FIR flexibility in .NET IR just as the JVM FIR2IR pipeline does for Java types. */
class DotNetIrSpecialAnnotationSymbolProvider(
    private val builtInsModule: IrModuleFragment,
) : IrSpecialAnnotationsProvider() {
    private val packages = mutableMapOf<FqName, IrExternalPackageFragmentImpl>()
    private val enhancedNullability = EnhancedNullability.annotationInfo()
    private val flexibleNullability = FlexibleNullability.annotationInfo()
    private val flexibleMutability = FlexibleMutability.annotationInfo()
    private val flexibleArrayElementVariance = FlexibleArrayElementVariance.annotationInfo()
    private val rawType = RawTypeAnnotation.annotationInfo()

    override fun generateEnhancedNullabilityAnnotation(): IrAnnotation = enhancedNullability.annotation()

    override fun generateFlexibleNullabilityAnnotation(): IrAnnotation = flexibleNullability.annotation()

    override fun generateFlexibleMutabilityAnnotation(): IrAnnotation = flexibleMutability.annotation()

    override fun generateFlexibleArrayElementVarianceAnnotation(): IrAnnotation =
        flexibleArrayElementVariance.annotation()

    override fun generateRawTypeAnnotation(): IrAnnotation = rawType.annotation()

    private fun ClassId.annotationInfo(): AnnotationInfo {
        val irPackage = packages.getOrPut(packageFqName) {
            IrExternalPackageFragmentImpl(
                DescriptorlessExternalPackageFragmentSymbol(),
                packageFqName,
                builtInsModule,
            )
        }
        val classSymbol = IrFactoryImpl.buildClass {
            kind = ClassKind.ANNOTATION_CLASS
            name = shortClassName
        }.apply {
            createThisReceiverParameter()
            parent = irPackage
            addConstructor { isPrimary = true }
        }.symbol
        return AnnotationInfo(
            classSymbol.defaultType,
            classSymbol.owner.declarations.firstIsInstance<IrConstructor>().symbol,
        )
    }

    private data class AnnotationInfo(
        val type: IrType,
        val constructor: IrConstructorSymbol,
    ) {
        fun annotation(): IrAnnotation = IrAnnotationImpl.fromSymbolOwner(type, constructor)
    }
}
