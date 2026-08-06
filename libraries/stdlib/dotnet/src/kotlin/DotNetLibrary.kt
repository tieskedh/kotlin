/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

@PublishedApi
internal fun <T : Enum<T>> dotNetEnumValuesIntrinsic(): Array<T> =
    throw NotImplementedError("Implemented as a Kotlin/.NET compiler intrinsic")

@PublishedApi
internal fun <T : Enum<T>> dotNetEnumValueOfIntrinsic(name: String): T =
    throw NotImplementedError("Implemented as a Kotlin/.NET compiler intrinsic: $name")

/** Common declaration plus the JS/Wasm intrinsic-body architecture. */
public actual inline fun <reified T : Enum<T>> enumValues(): Array<T> =
    dotNetEnumValuesIntrinsic<T>()

/** Common declaration plus the JS/Wasm intrinsic-body architecture. */
public actual inline fun <reified T : Enum<T>> enumValueOf(name: String): T =
    dotNetEnumValueOfIntrinsic<T>(name)
