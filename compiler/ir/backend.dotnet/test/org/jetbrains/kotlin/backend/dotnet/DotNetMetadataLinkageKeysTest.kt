/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotNetMetadataLinkageKeysTest {
    @Test
    fun capturesConstructorOfZeroOwnParameterInnerBelowGenericOuter() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<genericInnerConstructorLinkageTest>"),
            LockBasedStorageManager("DotNetGenericInnerConstructorLinkageTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val file = IrFileImpl(
            NaiveSourceBasedFileEntryImpl("genericInner.kt"),
            IrFileSymbolImpl(),
            FqName("sample"),
            module,
        ).also { module.files += it }

        val genericOuter = publicClass("Outer", file)
        genericOuter.addTypeParameter { name = Name.identifier("T") }
        val capturingInner = publicClass("Plain", genericOuter, isInner = true)
        val capturingConstructor = IrFactoryImpl.buildConstructor {
            isPrimary = true
            visibility = DescriptorVisibilities.PUBLIC
            returnType = capturingInner.symbol.createType(false, emptyList())
        }.apply { parent = capturingInner }
        capturingInner.declarations += capturingConstructor
        genericOuter.declarations += capturingInner

        val staticNested = publicClass("StaticNested", genericOuter)
        val staticConstructor = IrFactoryImpl.buildConstructor {
            isPrimary = true
            visibility = DescriptorVisibilities.PUBLIC
            returnType = staticNested.symbol.createType(false, emptyList())
        }.apply { parent = staticNested }
        staticNested.declarations += staticConstructor
        genericOuter.declarations += staticNested
        file.declarations += genericOuter

        val linkageKeys = collectDotNetMetadataLinkageKeys(
            module,
            DotNetIlEmissionScope.USER,
            includeGenericOwnerConstructors = true,
        ) { false }

        assertTrue(capturingConstructor in linkageKeys)
        assertFalse(staticConstructor in linkageKeys)
        assertFalse(
            capturingConstructor in collectDotNetMetadataLinkageKeys(
                module,
                DotNetIlEmissionScope.USER,
                includeGenericOwnerConstructors = false,
            ) { false },
        )
    }

    private fun publicClass(
        name: String,
        parent: IrDeclarationParent,
        isInner: Boolean = false,
    ): IrClass = IrFactoryImpl.buildClass {
        this.name = Name.identifier(name)
        kind = ClassKind.CLASS
        modality = Modality.FINAL
        visibility = DescriptorVisibilities.PUBLIC
        this.isInner = isInner
    }.apply {
        this.parent = parent
    }
}
