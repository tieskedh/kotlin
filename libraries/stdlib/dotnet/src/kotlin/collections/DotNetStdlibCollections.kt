/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

import kotlin.io.Serializable

// The ordinary target actual of the common marker. It intentionally has no relationship to a
// CLR collection interface; algorithms use it only as a Kotlin-owned capability marker.
public interface RandomAccess

internal object EmptyIterator : ListIterator<Nothing> {
    override fun hasNext(): Boolean = false
    override fun hasPrevious(): Boolean = false
    override fun nextIndex(): Int = 0
    override fun previousIndex(): Int = -1
    override fun next(): Nothing = throw NoSuchElementException()
    override fun previous(): Nothing = throw NoSuchElementException()
}

// Kept as the same non-generic singleton as the common stdlib. Logical List<T> views therefore
// preserve ===; the ordinary split-interface bridge lowering supplies canonical erased slots and
// the List<Nothing> typed capabilities on this one object.
internal object EmptyList : List<Nothing>, Serializable, RandomAccess {
    override fun equals(other: Any?): Boolean = other is List<*> && other.isEmpty()
    override fun hashCode(): Int = 1
    override fun toString(): String = "[]"

    override val size: Int get() = 0
    override fun isEmpty(): Boolean = true
    override fun contains(element: Nothing): Boolean = false
    override fun containsAll(elements: Collection<Nothing>): Boolean = elements.isEmpty()

    override fun get(index: Int): Nothing =
        throw IndexOutOfBoundsException("Empty list doesn't contain element at index $index.")

    override fun indexOf(element: Nothing): Int = -1
    override fun lastIndexOf(element: Nothing): Int = -1

    override fun iterator(): Iterator<Nothing> = EmptyIterator
    override fun listIterator(): ListIterator<Nothing> = EmptyIterator

    override fun listIterator(index: Int): ListIterator<Nothing> {
        if (index != 0) throw IndexOutOfBoundsException("Index: $index")
        return EmptyIterator
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<Nothing> {
        if (fromIndex == 0 && toIndex == 0) return this
        throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex")
    }
}

public fun <T> emptyList(): List<T> = EmptyList

// Resolution-only declarations for the first array-operation slices. The backend intercepts every
// call through DotNetIlIntrinsicMethods and excludes these declarations from emitted facades.
// Keeping them external also leaves omitted defaults visible on the original call, so the
// intrinsic can preserve Kotlin's receiver/argument/default-expression evaluation order without
// generating a fake Kotlin implementation into each consumer assembly.

public external fun <T> Array<out T>.copyInto(
    destination: Array<T>,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): Array<T>

public external fun IntArray.copyInto(
    destination: IntArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): IntArray

public external fun LongArray.copyInto(
    destination: LongArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): LongArray

public external fun DoubleArray.copyInto(
    destination: DoubleArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): DoubleArray

public external fun BooleanArray.copyInto(
    destination: BooleanArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): BooleanArray

public external fun CharArray.copyInto(
    destination: CharArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): CharArray

public external fun <T> Array<T>.copyOf(): Array<T>
public external fun IntArray.copyOf(): IntArray
public external fun LongArray.copyOf(): LongArray
public external fun DoubleArray.copyOf(): DoubleArray
public external fun BooleanArray.copyOf(): BooleanArray
public external fun CharArray.copyOf(): CharArray

public external fun IntArray.copyOf(newSize: Int): IntArray
public external fun LongArray.copyOf(newSize: Int): LongArray
public external fun DoubleArray.copyOf(newSize: Int): DoubleArray
public external fun BooleanArray.copyOf(newSize: Int): BooleanArray
public external fun CharArray.copyOf(newSize: Int): CharArray

// Concrete reference substitutions map Array<T?> to the same exact CLR reference vector and are
// supported. An open T? still reaches the owning generic-array gate and fails explicitly.
public external fun <T> Array<T>.copyOf(newSize: Int): Array<T?>

// Shallow content equality follows the common stdlib contract: nullable arrays compare equal when
// both are null, elements use Kotlin equality, and nested arrays keep their identity-based equals.
public external infix fun <T> Array<out T>?.contentEquals(other: Array<out T>?): Boolean
public external infix fun IntArray?.contentEquals(other: IntArray?): Boolean
public external infix fun LongArray?.contentEquals(other: LongArray?): Boolean
public external infix fun DoubleArray?.contentEquals(other: DoubleArray?): Boolean
public external infix fun BooleanArray?.contentEquals(other: BooleanArray?): Boolean
public external infix fun CharArray?.contentEquals(other: CharArray?): Boolean

// Deep equality is defined only on generic arrays; nested generic arrays recurse, supported
// primitive arrays use their shallow content contract, and all other elements use Kotlin equals.
public external infix fun <T> Array<out T>?.contentDeepEquals(other: Array<out T>?): Boolean

// Content hashes use the List-compatible 31-fold. The shallow family hashes nested arrays by
// identity; the deep generic-array operation recursively hashes supported nested array shapes.
public external fun <T> Array<out T>?.contentHashCode(): Int
public external fun IntArray?.contentHashCode(): Int
public external fun LongArray?.contentHashCode(): Int
public external fun DoubleArray?.contentHashCode(): Int
public external fun BooleanArray?.contentHashCode(): Int
public external fun CharArray?.contentHashCode(): Int
public external fun <T> Array<out T>?.contentDeepHashCode(): Int

// Shallow text keeps nested arrays as ordinary identity-rendered elements. Deep text recursively
// renders supported nested arrays and replaces only active recursion-path cycles with "[...]".
public external fun <T> Array<out T>?.contentToString(): String
public external fun IntArray?.contentToString(): String
public external fun LongArray?.contentToString(): String
public external fun DoubleArray?.contentToString(): String
public external fun BooleanArray?.contentToString(): String
public external fun CharArray?.contentToString(): String
public external fun <T> Array<out T>?.contentDeepToString(): String

public external fun <T> Array<out T>.asIterable(): Iterable<T>
public external fun IntArray.asIterable(): Iterable<Int>
public external fun LongArray.asIterable(): Iterable<Long>
public external fun DoubleArray.asIterable(): Iterable<Double>
public external fun BooleanArray.asIterable(): Iterable<Boolean>
public external fun CharArray.asIterable(): Iterable<Char>

// Compiler-facing factories are Kotlin-internal but metadata-public: generated user assemblies
// call them across the Kotlin.Stdlib boundary without making the implementation classes part of
// the compiler ABI. This follows the JVM/JS helper boundary for array iteration.
internal fun <T> dotNetArrayIterator(array: Array<T>): Iterator<T> = ArrayIterator(array)

internal fun <T> dotNetArrayIterable(array: Array<T>): Iterable<T> =
    if (array.size == 0) emptyList() else ArrayIterable(array)

// The first executable target-stdlib implementation. It is private in both Kotlin source and CLR
// metadata. Its Iterator MethodImpl bridges are generated by the same lowering as for an ordinary
// user class; only the factory above crosses the assembly boundary.
private class ArrayIterator<T>(private val array: Array<T>) : Iterator<T> {
    private var index: Int = 0

    override fun hasNext(): Boolean = index < array.size

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        return array[index++]
    }
}

// The factory above owns the common empty-array singleton fast path. Non-empty arrays use this
// ordinary view class, whose bridge shape is shared with user-defined Iterable implementations.
private class ArrayIterable<T>(private val array: Array<T>) : Iterable<T> {
    override fun iterator(): Iterator<T> = ArrayIterator(array)
}
