/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dotnet

import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.scopes.FirOverrideChecker
import org.jetbrains.kotlin.fir.scopes.impl.FirStandardOverrideChecker
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.varargElementType
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * The narrow foreign-CLR counterpart of Native's platform override checker.
 *
 * A reified owner substitution can turn an imported `T!` vararg into the flexible primitive
 * `Int!`. The standard Kotlin checker does not consider a rigid `Int` implementation equal to
 * that platform type, even though both declarations fill the same exact CLR `int32[]` slot.
 * Accept only the lower, non-null carrier of such a retained foreign declaration. Reference
 * nullability, bounds, method arity, receiver shape, and every other ordinary override rule stay
 * with the standard checker and frontend diagnostics.
 */
class DotNetClrOverrideChecker(
    private val session: FirSession,
) : FirOverrideChecker {
    private val standardOverrideChecker = FirStandardOverrideChecker(session)

    override fun isOverriddenFunction(
        overrideCandidate: FirNamedFunction,
        baseDeclaration: FirNamedFunction,
    ): Boolean {
        if (standardOverrideChecker.isOverriddenFunction(overrideCandidate, baseDeclaration)) {
            return true
        }
        val importedBase = baseDeclaration.unwrapFakeOverrides()
        if (importedBase.containerSource !is DotNetClrImportedDeclarationSource) return false
        if (
            overrideCandidate.name != baseDeclaration.name ||
            overrideCandidate.receiverParameter != null ||
            baseDeclaration.receiverParameter != null ||
            overrideCandidate.contextParameters.isNotEmpty() ||
            baseDeclaration.contextParameters.isNotEmpty() ||
            overrideCandidate.valueParameters.size != baseDeclaration.valueParameters.size ||
            overrideCandidate.valueParameters.map { parameter -> parameter.isVararg } !=
                    baseDeclaration.valueParameters.map { parameter -> parameter.isVararg }
        ) {
            return false
        }
        val substitutor = standardOverrideChecker.buildTypeParametersSubstitutorIfCompatible(
            overrideCandidate,
            baseDeclaration,
        ) ?: return false
        return overrideCandidate.valueParameters.zip(baseDeclaration.valueParameters).all { parameterPair ->
            val candidate = parameterPair.first
            val base = parameterPair.second
            val candidateType = candidate.returnTypeRef.coneType.let { type ->
                if (candidate.isVararg) type.varargElementType() else type
            }
            val baseType = base.returnTypeRef.coneType.let { type ->
                if (base.isVararg) type.varargElementType() else type
            }
            val substitutedCandidate = substitutor.substituteOrSelf(candidateType)
            val substitutedBase = substitutor.substituteOrSelf(baseType)
            AbstractTypeChecker.equalTypes(session.typeContext, substitutedCandidate, substitutedBase) ||
                    substitutedBase is ConeFlexibleType &&
                    substitutedBase.lowerBoundIfFlexible().isPrimitiveOrNullablePrimitive &&
                    AbstractTypeChecker.equalTypes(
                        session.typeContext,
                        substitutedCandidate,
                        substitutedBase.lowerBoundIfFlexible(),
                    )
        }
    }

    override fun isOverriddenProperty(
        overrideCandidate: FirCallableDeclaration,
        baseDeclaration: FirProperty,
    ): Boolean = standardOverrideChecker.isOverriddenProperty(overrideCandidate, baseDeclaration)

    override fun chooseIntersectionVisibility(
        overrides: Collection<FirCallableSymbol<*>>,
        dispatchClassSymbol: FirRegularClassSymbol?,
    ): Visibility = standardOverrideChecker.chooseIntersectionVisibility(overrides, dispatchClassSymbol)
}
