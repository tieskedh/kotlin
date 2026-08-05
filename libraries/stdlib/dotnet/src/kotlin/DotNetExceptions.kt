/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

// These are the target-specific actuals that common-non-jvm Exceptions.kt deliberately leaves to
// each platform. Their constructor sets follow the mature JS/Wasm implementations. Physical CLR
// identities live in Kotlin.Runtime and are selected by DotNetMappedExceptions.

public actual open class AssertionError : Error {
    public actual constructor() : super()
    public constructor(message: String?) : super(message)
    public actual constructor(message: Any?) : super(message?.toString(), message as? Throwable)

    @SinceKotlin("1.4")
    public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

public actual open class NoWhenBranchMatchedException : RuntimeException {
    public actual constructor() : super()
    public actual constructor(message: String?) : super(message)
    public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
    public actual constructor(cause: Throwable?) : super(cause)
}

public actual class UninitializedPropertyAccessException : RuntimeException {
    public actual constructor() : super()
    public actual constructor(message: String?) : super(message)
    public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
    public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class OutOfMemoryError : Error {
    public actual constructor() : super()
    public actual constructor(message: String?) : super(message)
}

@SinceKotlin("1.4")
public actual fun Throwable.stackTraceToString(): String = dotNetStackTraceToString(this)

@SinceKotlin("1.4")
public actual fun Throwable.printStackTrace(): Unit = dotNetPrintStackTrace(this)

@SinceKotlin("1.4")
public actual fun Throwable.addSuppressed(exception: Throwable): Unit =
    dotNetAddSuppressed(this, exception)

@SinceKotlin("1.4")
public actual val Throwable.suppressedExceptions: List<Throwable>
    get() {
        val snapshot = dotNetSuppressedExceptions(this)
        return if (snapshot.size == 0) emptyList() else SuppressedExceptionList(snapshot)
    }

// Compiler intrinsics bind these source-level calls to the versioned Kotlin.Runtime service.
// Keeping the public actuals ordinary Kotlin functions makes Kotlin.Stdlib the physical owner of
// the Common API while every throwable object — Kotlin-owned or foreign — uses one runtime state.
private external fun dotNetStackTraceToString(exception: Throwable): String
private external fun dotNetPrintStackTrace(exception: Throwable): Unit
private external fun dotNetAddSuppressed(owner: Throwable, exception: Throwable): Unit
private external fun dotNetSuppressedExceptions(exception: Throwable): Array<Throwable>

// A snapshot never observes later additions, just like Throwable.getSuppressed() on the JVM.
// Sub-lists are immutable views of the same private snapshot; no mutable foreign collection leaks.
private class SuppressedExceptionList(
    private val elements: Array<Throwable>,
    private val offset: Int = 0,
    override val size: Int = elements.size,
) : List<Throwable> {
    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: Throwable): Boolean = indexOf(element) >= 0

    override fun containsAll(elements: Collection<Throwable>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun get(index: Int): Throwable {
        checkIndex(index)
        return elements[offset + index]
    }

    override fun indexOf(element: Throwable): Int {
        var index = 0
        while (index < size) {
            if (elements[offset + index] == element) return index
            index++
        }
        return -1
    }

    override fun lastIndexOf(element: Throwable): Int {
        var index = size - 1
        while (index >= 0) {
            if (elements[offset + index] == element) return index
            index--
        }
        return -1
    }

    override fun iterator(): Iterator<Throwable> = SuppressedExceptionIterator(this, 0)

    override fun listIterator(): ListIterator<Throwable> = SuppressedExceptionIterator(this, 0)

    override fun listIterator(index: Int): ListIterator<Throwable> {
        if (index < 0 || index > size) {
            throw IndexOutOfBoundsException("Index: $index, size: $size")
        }
        return SuppressedExceptionIterator(this, index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<Throwable> {
        if (fromIndex < 0 || toIndex > size) {
            throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex, size: $size")
        }
        if (fromIndex > toIndex) {
            throw IllegalArgumentException("fromIndex: $fromIndex is greater than toIndex: $toIndex")
        }
        if (fromIndex == toIndex) return emptyList()
        if (fromIndex == 0 && toIndex == size) return this
        return SuppressedExceptionList(elements, offset + fromIndex, toIndex - fromIndex)
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is List<*> || other.size != size) return false
        var index = 0
        while (index < size) {
            if (elements[offset + index] != other[index]) return false
            index++
        }
        return true
    }

    override fun hashCode(): Int {
        var hashCode = 1
        var index = 0
        while (index < size) {
            hashCode = 31 * hashCode + elements[offset + index].hashCode()
            index++
        }
        return hashCode
    }

    override fun toString(): String {
        var result = "["
        var index = 0
        while (index < size) {
            if (index != 0) result += ", "
            result += elements[offset + index].toString()
            index++
        }
        return result + "]"
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, size: $size")
        }
    }
}

private class SuppressedExceptionIterator(
    private val list: SuppressedExceptionList,
    private var index: Int,
) : ListIterator<Throwable> {
    override fun hasNext(): Boolean = index < list.size
    override fun hasPrevious(): Boolean = index > 0
    override fun nextIndex(): Int = index
    override fun previousIndex(): Int = index - 1

    override fun next(): Throwable {
        if (!hasNext()) throw NoSuchElementException()
        return list[index++]
    }

    override fun previous(): Throwable {
        if (!hasPrevious()) throw NoSuchElementException()
        return list[--index]
    }
}
