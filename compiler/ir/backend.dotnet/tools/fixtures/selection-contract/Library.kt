/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package selection.contract

interface Producer<out T> { fun read(): T }

class Box<T>(var value: T)

class ObjectBox<T>(initial: T) {
    private var storage: Any? = initial

    var value: T
        @Suppress("UNCHECKED_CAST")
        get() = storage as T
        set(value) { storage = value }
}

fun <T> forward(value: T): T = value
fun erase(value: Any?): Any? = value
fun <T> genericErase(value: T): Any? = value

@Suppress("UNCHECKED_CAST")
fun <T> throughObject(value: T): T {
    val stored: Any? = value
    return stored as T
}
