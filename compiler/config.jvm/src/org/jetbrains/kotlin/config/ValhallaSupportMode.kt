/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

enum class ValhallaSupportMode(val description: String) {
    PRIMITIVES("primitives");

    companion object {
        @JvmStatic
        fun fromStringOrNull(string: String?): ValhallaSupportMode? = entries.find { it.description == string }
    }
}

val LanguageVersionSettings.valhallaSupportMode: ValhallaSupportMode?
    get() = getFlag(JvmAnalysisFlags.valhallaSupport)
