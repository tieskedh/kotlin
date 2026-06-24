/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.backend.common.IdSignaturesExtractor
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.metadata.isCInteropLibrary

/**
 * This is a lightweight tool that allows extracting [IdSignature]s from the given C-interop [KotlinLibrary].
 */
@OptIn(K1Deprecation::class)
class IdSignaturesExtractorFromCInteropKlib(library: KotlinLibrary) : IdSignaturesExtractor {
    init {
        check(library.isCInteropLibrary()) { "Not a C-interop library: $library" }
    }

    override fun extractAllPublicSignatures(): IdSignaturesExtractor.ExtractedSignatures {
        TODO("Not yet implemented")
    }

    override fun extractOnlyTopLevelPublicSignatures(): IdSignaturesExtractor.ExtractedSignatures {
        TODO("Not yet implemented")
    }
}
