/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.backend.common.IdSignaturesExtractor
import org.jetbrains.kotlin.backend.common.IdSignaturesExtractor.ExtractedSignatures
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.metadata.isCInteropLibrary
import org.jetbrains.kotlin.library.packageFqName
import org.jetbrains.kotlin.name.FqName

/**
 * This is a lightweight tool that allows extracting [IdSignature]s from the given C-interop [KotlinLibrary].
 */
@OptIn(K1Deprecation::class)
class IdSignaturesExtractorFromCInteropKlib(library: KotlinLibrary) : IdSignaturesExtractor {
    init {
        check(library.isCInteropLibrary()) { "Not a C-interop library: $library" }
    }

    private val packageFragment = IrExternalPackageFragmentImpl(
        symbol = IrExternalPackageFragmentSymbolImpl(),
        packageFqName = library.packageFqName?.let(::FqName) ?: error("C-interop library without the package name"),
    )

    private val symbolTable = SymbolTable(signaturer = null, IrFactoryImpl)
    private val declarationTracker = CInteropKlibMetadata2IRTransformer.DeclarationTracker()

    private val transformer = CInteropKlibMetadata2IRTransformer(
        symbolTable = symbolTable,
        symbols = CInteropKlibMetadata2IRTransformer.ExternalSymbols(symbolTable),
        declarationTracker = declarationTracker,
        getNestedKmClass = TODO(),
        getOrCreateContainingPackageFragment = { packageFragment },
        getReferencedDeclarationSymbol = TODO(),
        irProviderForLazyAnnotations = TODO()
    )

    override fun extractAllPublicSignatures(): ExtractedSignatures {
        TODO("Not yet implemented")
    }

    override fun extractOnlyTopLevelPublicSignatures(): ExtractedSignatures {
        TODO("Not yet implemented")
    }
}
