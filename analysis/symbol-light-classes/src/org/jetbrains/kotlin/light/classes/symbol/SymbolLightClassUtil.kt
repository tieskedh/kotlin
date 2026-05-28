/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.light.classes.symbol

import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.useSiteModule
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * A use-site-module-aware version of [org.jetbrains.kotlin.asJava.LightClassUtil].
 */
@OptIn(KaContextParameterApi::class)
@KaImplementationDetail
object SymbolLightClassUtil {
    context(_: KaSession)
    fun getLightClassTypeParameter(typeParameter: KaTypeParameterSymbol): List<PsiTypeParameter> {
        val enclosingDeclaration = typeParameter.containingDeclaration ?: return emptyList()
        val paramIndex = enclosingDeclaration.typeParameters.indexOf(typeParameter)

        val lightOwners = when (enclosingDeclaration) {
            is KaClassSymbol -> listOf(enclosingDeclaration.asPsiClass())
            is KaFunctionSymbol -> enclosingDeclaration.asPsiMethods()
            else -> emptyList()
        }

        return lightOwners.mapNotNull { lightOwner ->
            (lightOwner as? PsiTypeParameterListOwner)?.typeParameters?.getOrNull(paramIndex)
        }
    }

    context(_: KaSession)
    fun getLightClassParameters(parameterSymbol: KaParameterSymbol): List<PsiParameter> {
        val enclosingDeclaration = parameterSymbol.containingDeclaration as? KaFunctionSymbol ?: return emptyList()

        val methods = enclosingDeclaration.asPsiMethods()

        return methods.mapNotNull { method ->
            method.parameterList.parameters.firstOrNull { parameter ->
                parameter.matches(parameterSymbol)
            }
        }
    }

    context(_: KaSession)
    fun getLightClassBackingField(declaration: KaSymbol): PsiField? {
        if (declaration !is KtClassOrObject && declaration !is KaEnumEntrySymbol && declaration !is KaBackingFieldSymbol) return null
        var psiClass: PsiClass = getWrappingClass(declaration) ?: return null

        if (psiClass is KtLightClass && psiClass is KaSymbolJavaView<*>) {
            psiClass.symbolPointer?.withSymbol(useSiteModule) { originSymbol ->
                if (originSymbol is KaClassSymbol && originSymbol.classKind == KaClassKind.COMPANION_OBJECT) {
                    val containingClass = originSymbol.containingSymbol
                    if (containingClass is KaClassSymbol) {
                        val containingLightClass = containingClass.asPsiClass()
                        if (containingLightClass != null) {
                            psiClass = containingLightClass
                        }
                    }
                }
            }
        }

        return psiClass.fields.find { psiField: PsiField ->
            psiField.matches(declaration)
        }
    }

    context(_: KaSession)
    fun getLightClassMethods(
        declaration: KaFunctionSymbol,
    ): List<KtLightMethod> {
        return getWrappingClasses(declaration)
            .flatMap { it.methods.asSequence() }
            .filterIsInstance<KtLightMethod>()
            .filter { lightMethod ->
                lightMethod.matches(declaration)
            }
    }

    context(_: KaSession)
    private fun getWrappingClass(declaration: KaSymbol): PsiClass? {
        return when (declaration.location) {
            KaSymbolLocation.TOP_LEVEL -> declaration.containingFile?.asFacadePsiClass()
            KaSymbolLocation.CLASS -> (declaration.containingDeclaration as? KaClassSymbol)?.asPsiClass()
            KaSymbolLocation.PROPERTY -> declaration.containingSymbol?.let { property -> getWrappingClass(property) }
            KaSymbolLocation.LOCAL -> declaration.containingDeclaration?.let { declaration -> getWrappingClass(declaration) }
        }
    }

    context(_: KaSession)
    private fun getWrappingClasses(declaration: KaSymbol): List<PsiClass> {
        val wrapperClass = getWrappingClass(declaration) ?: return emptyList()
        return (wrapperClass as? KaSymbolJavaView<*>)?.symbolPointer?.withSymbol(useSiteModule) { originSymbol ->
            if (originSymbol is KaClassSymbol && originSymbol.classKind == KaClassKind.COMPANION_OBJECT && wrapperClass.parent is PsiClass) {
                listOf(wrapperClass, wrapperClass.parent as PsiClass)
            } else {
                null
            }
        } ?: listOf(wrapperClass)
    }

    context(_: KaSession)
    private fun PsiElement.matches(symbol: KaSymbol): Boolean {
        if (this !is KaSymbolJavaView<*>) {
            return false
        }
        val symbolPsi = symbol.psi

        return symbolPsi != null && kotlinOrigin == symbolPsi || symbolPointer?.pointsToTheSameSymbolAs(symbol.createPointer()) == true
    }
}
