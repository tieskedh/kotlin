/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.enums

// CLR serialization is not a Kotlin EnumEntries contract. Match Native/Wasm with an inert actual
// so the authoritative Common implementation keeps its private proxy boundary unchanged.
internal actual class EnumEntriesSerializationProxy<E : Enum<E>> actual constructor(entries: Array<E>)
