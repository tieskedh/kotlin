/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.decompiled.light.classes

import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.LightMemberOriginForCompiledField
import org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.LightMemberOriginForCompiledMethod
import org.jetbrains.kotlin.analysis.decompiler.psi.file.KtClsFile
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor

/**
 * A strategy for constructing decompiled light elements.
 *
 * It allows higher-level modules (for instance, the Analysis API symbol light classes) to substitute their own
 * subclasses — e.g., ones implementing `KaSymbolJavaView` — throughout the whole decompiled light-class tree,
 * without this module depending on the Analysis API.
 *
 * [Default] reproduces the plain decompiled light classes.
 */
interface DecompiledLightClassFactory {
    fun createClass(
        clsDelegate: PsiClass,
        clsParent: PsiElement,
        file: KtClsFile,
        kotlinOrigin: KtClassOrObject?,
    ): KtLightClassForDecompiledDeclaration

    fun createFacade(
        clsDelegate: PsiClass,
        clsParent: PsiElement,
        file: KtClsFile,
        kotlinOrigin: KtClassOrObject?,
        files: Collection<KtFile>,
    ): KtLightClassForDecompiledFacade

    fun createEnumEntryInitializerClass(
        psiConstantInitializer: PsiEnumConstantInitializer,
        enumConstant: KtLightEnumEntryForDecompiledDeclaration,
        clsParent: KtLightClass,
        file: KtClsFile,
        kotlinOrigin: KtClassOrObject?,
    ): KtLightEnumClassForDecompiledDeclaration

    fun createMethod(
        funDelegate: PsiMethod,
        funParent: KtLightClass,
        lightMemberOrigin: LightMemberOriginForCompiledMethod,
    ): KtLightMethodForDecompiledDeclaration

    fun createField(
        fldDelegate: PsiField,
        fldParent: KtLightClass,
        lightMemberOrigin: LightMemberOriginForCompiledField,
    ): KtLightFieldForDecompiledDeclaration

    fun createEnumEntry(
        fldDelegate: PsiEnumConstant,
        fldParent: KtLightClassForDecompiledDeclaration,
        lightMemberOrigin: LightMemberOriginForCompiledField,
        file: KtClsFile,
    ): KtLightEnumEntryForDecompiledDeclaration

    fun createRecordHeader(
        clsDelegate: PsiRecordHeader,
        containingClass: KtLightClassForDecompiledDeclarationBase,
        kotlinOrigin: KtPrimaryConstructor?,
    ): KtLightRecordHeaderForDecompiledDeclaration

    fun createRecordComponent(
        clsDelegate: PsiRecordComponent,
        recordHeader: KtLightRecordHeaderForDecompiledDeclaration,
        containingClass: KtLightClassForDecompiledDeclarationBase,
        kotlinOrigin: KtParameter?,
    ): KtLightRecordComponentForDecompiledDeclaration

    companion object Default : DecompiledLightClassFactory {
        override fun createClass(
            clsDelegate: PsiClass,
            clsParent: PsiElement,
            file: KtClsFile,
            kotlinOrigin: KtClassOrObject?,
        ): KtLightClassForDecompiledDeclaration =
            KtLightClassForDecompiledDeclaration(clsDelegate, clsParent, file, kotlinOrigin, factory = this)

        override fun createFacade(
            clsDelegate: PsiClass,
            clsParent: PsiElement,
            file: KtClsFile,
            kotlinOrigin: KtClassOrObject?,
            files: Collection<KtFile>,
        ): KtLightClassForDecompiledFacade =
            KtLightClassForDecompiledFacade(clsDelegate, clsParent, file, kotlinOrigin, files, factory = this)

        override fun createEnumEntryInitializerClass(
            psiConstantInitializer: PsiEnumConstantInitializer,
            enumConstant: KtLightEnumEntryForDecompiledDeclaration,
            clsParent: KtLightClass,
            file: KtClsFile,
            kotlinOrigin: KtClassOrObject?,
        ): KtLightEnumClassForDecompiledDeclaration =
            KtLightEnumClassForDecompiledDeclaration(psiConstantInitializer, enumConstant, clsParent, file, kotlinOrigin, factory = this)

        override fun createMethod(
            funDelegate: PsiMethod,
            funParent: KtLightClass,
            lightMemberOrigin: LightMemberOriginForCompiledMethod,
        ): KtLightMethodForDecompiledDeclaration =
            KtLightMethodForDecompiledDeclaration(funDelegate, funParent, lightMemberOrigin)

        override fun createField(
            fldDelegate: PsiField,
            fldParent: KtLightClass,
            lightMemberOrigin: LightMemberOriginForCompiledField,
        ): KtLightFieldForDecompiledDeclaration =
            KtLightFieldForDecompiledDeclaration(fldDelegate, fldParent, lightMemberOrigin)

        override fun createEnumEntry(
            fldDelegate: PsiEnumConstant,
            fldParent: KtLightClassForDecompiledDeclaration,
            lightMemberOrigin: LightMemberOriginForCompiledField,
            file: KtClsFile,
        ): KtLightEnumEntryForDecompiledDeclaration =
            KtLightEnumEntryForDecompiledDeclaration(fldDelegate, fldParent, lightMemberOrigin, file)

        override fun createRecordHeader(
            clsDelegate: PsiRecordHeader,
            containingClass: KtLightClassForDecompiledDeclarationBase,
            kotlinOrigin: KtPrimaryConstructor?,
        ): KtLightRecordHeaderForDecompiledDeclaration =
            KtLightRecordHeaderForDecompiledDeclaration(clsDelegate, containingClass, kotlinOrigin, factory = this)

        override fun createRecordComponent(
            clsDelegate: PsiRecordComponent,
            recordHeader: KtLightRecordHeaderForDecompiledDeclaration,
            containingClass: KtLightClassForDecompiledDeclarationBase,
            kotlinOrigin: KtParameter?,
        ): KtLightRecordComponentForDecompiledDeclaration =
            KtLightRecordComponentForDecompiledDeclaration(clsDelegate, recordHeader, containingClass, kotlinOrigin)
    }
}
