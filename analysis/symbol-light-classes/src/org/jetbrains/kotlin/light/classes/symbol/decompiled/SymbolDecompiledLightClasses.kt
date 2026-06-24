/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(KaExperimentalApi::class, KaImplementationDetail::class)

package org.jetbrains.kotlin.light.classes.symbol.decompiled

import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.decompiled.light.classes.*
import org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.LightMemberOriginForCompiledField
import org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.LightMemberOriginForCompiledMethod
import org.jetbrains.kotlin.analysis.decompiler.psi.file.KtClsFile
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.light.classes.symbol.KaSymbolJavaView
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor

/**
 * Builds Analysis-API-aware decompiled light classes.
 *
 * They behave exactly like the plain decompiled light classes, but additionally implement [KaSymbolJavaView] so that the result of
 * `KaClassSymbol.asPsiClass()` / `asFacadePsiClass()` / `asPsiMethods()` / `asPsiField()` is uniformly a
 * [KaSymbolJavaView] regardless of whether the declaration comes from sources or a compiled library.
 *
 * Decompiled light classes are built outside of an analysis session, so [KaSymbolJavaView.symbolPointer]
 * and [KaSymbolJavaView.useSiteModule] are always `null`.
 * Consumers rely on [KaSymbolJavaView.kotlinOrigin] for matching.
 */
internal object SymbolDecompiledLightClassFactory : DecompiledLightClassFactory {
    override fun createClass(
        clsDelegate: PsiClass,
        clsParent: PsiElement,
        file: KtClsFile,
        kotlinOrigin: KtClassOrObject?,
    ): KtLightClassForDecompiledDeclaration =
        SymbolLightClassForDecompiledDeclaration(clsDelegate, clsParent, file, kotlinOrigin)

    override fun createFacade(
        clsDelegate: PsiClass,
        clsParent: PsiElement,
        file: KtClsFile,
        kotlinOrigin: KtClassOrObject?,
        files: Collection<KtFile>,
    ): KtLightClassForDecompiledFacade =
        SymbolLightClassForDecompiledFacade(clsDelegate, clsParent, file, kotlinOrigin, files)

    override fun createEnumEntryInitializerClass(
        psiConstantInitializer: PsiEnumConstantInitializer,
        enumConstant: KtLightEnumEntryForDecompiledDeclaration,
        clsParent: KtLightClass,
        file: KtClsFile,
        kotlinOrigin: KtClassOrObject?,
    ): KtLightEnumClassForDecompiledDeclaration =
        SymbolLightEnumClassForDecompiledDeclaration(psiConstantInitializer, enumConstant, clsParent, file, kotlinOrigin)

    override fun createMethod(
        funDelegate: PsiMethod,
        funParent: KtLightClass,
        lightMemberOrigin: LightMemberOriginForCompiledMethod,
    ): KtLightMethodForDecompiledDeclaration =
        SymbolLightMethodForDecompiledDeclaration(funDelegate, funParent, lightMemberOrigin)

    override fun createField(
        fldDelegate: PsiField,
        fldParent: KtLightClass,
        lightMemberOrigin: LightMemberOriginForCompiledField,
    ): KtLightFieldForDecompiledDeclaration =
        SymbolLightFieldForDecompiledDeclaration(fldDelegate, fldParent, lightMemberOrigin)

    override fun createEnumEntry(
        fldDelegate: PsiEnumConstant,
        fldParent: KtLightClassForDecompiledDeclaration,
        lightMemberOrigin: LightMemberOriginForCompiledField,
        file: KtClsFile,
    ): KtLightEnumEntryForDecompiledDeclaration =
        SymbolLightEnumEntryForDecompiledDeclaration(fldDelegate, fldParent, lightMemberOrigin, file)

    override fun createRecordHeader(
        clsDelegate: PsiRecordHeader,
        containingClass: KtLightClassForDecompiledDeclarationBase,
        kotlinOrigin: KtPrimaryConstructor?,
    ): KtLightRecordHeaderForDecompiledDeclaration =
        SymbolLightRecordHeaderForDecompiledDeclaration(clsDelegate, containingClass, kotlinOrigin)

    override fun createRecordComponent(
        clsDelegate: PsiRecordComponent,
        recordHeader: KtLightRecordHeaderForDecompiledDeclaration,
        containingClass: KtLightClassForDecompiledDeclarationBase,
        kotlinOrigin: KtParameter?,
    ): KtLightRecordComponentForDecompiledDeclaration =
        SymbolLightRecordComponentForDecompiledDeclaration(clsDelegate, recordHeader, containingClass, kotlinOrigin)
}

private class SymbolLightClassForDecompiledDeclaration(
    clsDelegate: PsiClass,
    clsParent: PsiElement,
    file: KtClsFile,
    kotlinOrigin: KtClassOrObject?,
) : KtLightClassForDecompiledDeclaration(clsDelegate, clsParent, file, kotlinOrigin, SymbolDecompiledLightClassFactory),
    KaSymbolJavaView<KaNamedClassSymbol> {
    override val symbolPointer: KaSymbolPointer<KaNamedClassSymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightClassForDecompiledFacade(
    clsDelegate: PsiClass,
    clsParent: PsiElement,
    file: KtClsFile,
    kotlinOrigin: KtClassOrObject?,
    files: Collection<KtFile>,
) : KtLightClassForDecompiledFacade(clsDelegate, clsParent, file, kotlinOrigin, files, SymbolDecompiledLightClassFactory),
    KaSymbolJavaView<KaFileSymbol> {
    override val symbolPointer: KaSymbolPointer<KaFileSymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightEnumClassForDecompiledDeclaration(
    psiConstantInitializer: PsiEnumConstantInitializer,
    enumConstant: KtLightEnumEntryForDecompiledDeclaration,
    clsParent: KtLightClass,
    file: KtClsFile,
    kotlinOrigin: KtClassOrObject?,
) : KtLightEnumClassForDecompiledDeclaration(
    psiConstantInitializer,
    enumConstant,
    clsParent,
    file,
    kotlinOrigin,
    SymbolDecompiledLightClassFactory,
), KaSymbolJavaView<KaEnumEntrySymbol> {
    override val symbolPointer: KaSymbolPointer<KaEnumEntrySymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightMethodForDecompiledDeclaration(
    funDelegate: PsiMethod,
    funParent: KtLightClass,
    lightMemberOrigin: LightMemberOriginForCompiledMethod,
) : KtLightMethodForDecompiledDeclaration(funDelegate, funParent, lightMemberOrigin),
    KaSymbolJavaView<KaFunctionSymbol> {
    override val symbolPointer: KaSymbolPointer<KaFunctionSymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightFieldForDecompiledDeclaration(
    fldDelegate: PsiField,
    fldParent: KtLightClass,
    lightMemberOrigin: LightMemberOriginForCompiledField,
) : KtLightFieldForDecompiledDeclaration(fldDelegate, fldParent, lightMemberOrigin),
    KaSymbolJavaView<KaCallableSymbol> {
    override val symbolPointer: KaSymbolPointer<KaCallableSymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightEnumEntryForDecompiledDeclaration(
    fldDelegate: PsiEnumConstant,
    fldParent: KtLightClassForDecompiledDeclaration,
    lightMemberOrigin: LightMemberOriginForCompiledField,
    file: KtClsFile,
) : KtLightEnumEntryForDecompiledDeclaration(fldDelegate, fldParent, lightMemberOrigin, file, SymbolDecompiledLightClassFactory),
    KaSymbolJavaView<KaEnumEntrySymbol> {
    override val symbolPointer: KaSymbolPointer<KaEnumEntrySymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightRecordHeaderForDecompiledDeclaration(
    clsDelegate: PsiRecordHeader,
    containingClass: KtLightClassForDecompiledDeclarationBase,
    kotlinOrigin: KtPrimaryConstructor?,
) : KtLightRecordHeaderForDecompiledDeclaration(clsDelegate, containingClass, kotlinOrigin, SymbolDecompiledLightClassFactory),
    KaSymbolJavaView<KaConstructorSymbol> {
    override val symbolPointer: KaSymbolPointer<KaConstructorSymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}

private class SymbolLightRecordComponentForDecompiledDeclaration(
    clsDelegate: PsiRecordComponent,
    recordHeader: KtLightRecordHeaderForDecompiledDeclaration,
    containingClass: KtLightClassForDecompiledDeclarationBase,
    kotlinOrigin: KtParameter?,
) : KtLightRecordComponentForDecompiledDeclaration(clsDelegate, recordHeader, containingClass, kotlinOrigin),
    KaSymbolJavaView<KaValueParameterSymbol> {
    override val symbolPointer: KaSymbolPointer<KaValueParameterSymbol>? get() = null
    override val useSiteModule: KaModule? get() = null
}
