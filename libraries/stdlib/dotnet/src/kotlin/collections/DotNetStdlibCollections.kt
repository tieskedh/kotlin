/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

// The ordinary target actual of the common marker. It intentionally has no relationship to a
// CLR collection interface; algorithms use it only as a Kotlin-owned capability marker.
public actual interface RandomAccess

@IgnorableReturnValue
@PublishedApi
internal actual fun checkIndexOverflow(index: Int): Int {
    if (index < 0) {
        throwIndexOverflow()
    }
    return index
}

@IgnorableReturnValue
@PublishedApi
internal actual fun checkCountOverflow(count: Int): Int {
    if (count < 0) {
        throwCountOverflow()
    }
    return count
}

internal actual fun collectionToArray(collection: Collection<*>): Array<Any?> =
    collectionToArrayCommonImpl(collection)

internal actual fun <T> collectionToArray(collection: Collection<*>, array: Array<T>): Array<T> =
    collectionToArrayCommonImpl(collection, array)

internal actual fun <T> arrayOfNulls(reference: Array<T>, size: Int): Array<T> =
    dotNetArrayOfNulls(reference, size)

internal actual fun <T> terminateCollectionToArray(
    collectionSize: Int,
    array: Array<T>,
): Array<T> = array

// CLR vectors retain their runtime element type. This target-private external operation allocates
// from that physical type, including across covariant foreign boundaries; the backend emits the
// BCL operation directly and does not publish this declaration.
private external fun <T> dotNetArrayOfNulls(reference: Array<T>, size: Int): Array<T>

/**
 * Returns a [List] that wraps the original array.
 *
 * Like the JVM, JS, Native, and Wasm actuals, this view retains the array instead of copying it.
 * The private view inherits the authoritative Common AbstractList algorithms while retaining the
 * original array, matching the other mature targets' aliasing behavior.
 */
public actual fun <T> Array<out T>.asList(): List<T> = ArrayAsList(this)

@kotlin.internal.InlineOnly
internal actual inline fun <T> Array<out T>.asArrayList(): ArrayList<T> {
    val result = ArrayList<T>(size)
    var index = 0
    while (index < size) {
        result.add(this[index])
        index++
    }
    return result
}

public actual fun <T> listOf(element: T): List<T> = arrayListOf(element)

// Common owns the expect. JS, Wasm, and Native generate this same list-level algorithm; unlike
// JVM, .NET has no truthful host collection identity on which to delegate it.
public actual fun <T> MutableList<T>.reverse(): Unit {
    val midPoint = (size / 2) - 1
    if (midPoint < 0) return
    var reverseIndex = lastIndex
    for (index in 0..midPoint) {
        val tmp = this[index]
        this[index] = this[reverseIndex]
        this[reverseIndex] = tmp
        reverseIndex--
    }
}

@PublishedApi
@SinceKotlin("1.3")
@kotlin.internal.InlineOnly
internal actual inline fun <E> buildListInternal(builderAction: MutableList<E>.() -> Unit): List<E> =
    ArrayList<E>().apply(builderAction).build()

@PublishedApi
@SinceKotlin("1.3")
@kotlin.internal.InlineOnly
internal actual inline fun <E> buildListInternal(
    capacity: Int,
    builderAction: MutableList<E>.() -> Unit,
): List<E> = ArrayList<E>(capacity).apply(builderAction).build()

private class ArrayAsList<T>(private val array: Array<out T>) : AbstractList<T>(), RandomAccess {
    override val size: Int
        get() = array.size

    override fun get(index: Int): T {
        AbstractList.checkElementIndex(index, size)
        return array[index]
    }
}

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

public external fun BooleanArray.copyInto(
    destination: BooleanArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): BooleanArray

public external fun ByteArray.copyInto(
    destination: ByteArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): ByteArray

public external fun ShortArray.copyInto(
    destination: ShortArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): ShortArray

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

public external fun FloatArray.copyInto(
    destination: FloatArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): FloatArray

public external fun DoubleArray.copyInto(
    destination: DoubleArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): DoubleArray

public external fun CharArray.copyInto(
    destination: CharArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): CharArray

public external fun <T> Array<T>.copyOf(): Array<T>
public external fun BooleanArray.copyOf(): BooleanArray
public external fun ByteArray.copyOf(): ByteArray
public external fun ShortArray.copyOf(): ShortArray
public external fun IntArray.copyOf(): IntArray
public external fun LongArray.copyOf(): LongArray
public external fun FloatArray.copyOf(): FloatArray
public external fun DoubleArray.copyOf(): DoubleArray
public external fun CharArray.copyOf(): CharArray

public external fun BooleanArray.copyOf(newSize: Int): BooleanArray
public external fun ByteArray.copyOf(newSize: Int): ByteArray
public external fun ShortArray.copyOf(newSize: Int): ShortArray
public external fun IntArray.copyOf(newSize: Int): IntArray
public external fun LongArray.copyOf(newSize: Int): LongArray
public external fun FloatArray.copyOf(newSize: Int): FloatArray
public external fun DoubleArray.copyOf(newSize: Int): DoubleArray
public external fun CharArray.copyOf(newSize: Int): CharArray

// Concrete reference substitutions map Array<T?> to the same exact CLR reference vector and are
// supported. An open T? still reaches the owning generic-array gate and fails explicitly.
public external fun <T> Array<T>.copyOf(newSize: Int): Array<T?>

// Shallow content equality follows the common stdlib contract: nullable arrays compare equal when
// both are null, elements use Kotlin equality, and nested arrays keep their identity-based equals.
public external infix fun <T> Array<out T>?.contentEquals(other: Array<out T>?): Boolean
public external infix fun BooleanArray?.contentEquals(other: BooleanArray?): Boolean
public external infix fun ByteArray?.contentEquals(other: ByteArray?): Boolean
public external infix fun ShortArray?.contentEquals(other: ShortArray?): Boolean
public external infix fun IntArray?.contentEquals(other: IntArray?): Boolean
public external infix fun LongArray?.contentEquals(other: LongArray?): Boolean
public external infix fun FloatArray?.contentEquals(other: FloatArray?): Boolean
public external infix fun DoubleArray?.contentEquals(other: DoubleArray?): Boolean
public external infix fun CharArray?.contentEquals(other: CharArray?): Boolean

// Deep equality is defined only on generic arrays; nested generic arrays recurse, supported
// primitive arrays use their shallow content contract, and all other elements use Kotlin equals.
public external infix fun <T> Array<out T>?.contentDeepEquals(other: Array<out T>?): Boolean

// Content hashes use the List-compatible 31-fold. The shallow family hashes nested arrays by
// identity; the deep generic-array operation recursively hashes supported nested array shapes.
public external fun <T> Array<out T>?.contentHashCode(): Int
public external fun BooleanArray?.contentHashCode(): Int
public external fun ByteArray?.contentHashCode(): Int
public external fun ShortArray?.contentHashCode(): Int
public external fun IntArray?.contentHashCode(): Int
public external fun LongArray?.contentHashCode(): Int
public external fun FloatArray?.contentHashCode(): Int
public external fun DoubleArray?.contentHashCode(): Int
public external fun CharArray?.contentHashCode(): Int
public external fun <T> Array<out T>?.contentDeepHashCode(): Int

// Shallow text keeps nested arrays as ordinary identity-rendered elements. Deep text recursively
// renders supported nested arrays and replaces only active recursion-path cycles with "[...]".
public external fun <T> Array<out T>?.contentToString(): String
public external fun BooleanArray?.contentToString(): String
public external fun ByteArray?.contentToString(): String
public external fun ShortArray?.contentToString(): String
public external fun IntArray?.contentToString(): String
public external fun LongArray?.contentToString(): String
public external fun FloatArray?.contentToString(): String
public external fun DoubleArray?.contentToString(): String
public external fun CharArray?.contentToString(): String
public external fun <T> Array<out T>?.contentDeepToString(): String

public external fun <T> Array<out T>.asIterable(): Iterable<T>
public external fun BooleanArray.asIterable(): Iterable<Boolean>
public external fun ByteArray.asIterable(): Iterable<Byte>
public external fun ShortArray.asIterable(): Iterable<Short>
public external fun IntArray.asIterable(): Iterable<Int>
public external fun LongArray.asIterable(): Iterable<Long>
public external fun FloatArray.asIterable(): Iterable<Float>
public external fun DoubleArray.asIterable(): Iterable<Double>
public external fun CharArray.asIterable(): Iterable<Char>

// Compiler-facing factories are Kotlin-internal but metadata-public: generated user assemblies
// call them across the Kotlin.Stdlib boundary without making the implementation classes part of
// the compiler ABI. This follows the JVM/JS helper boundary for array iteration.
internal fun <T> dotNetArrayIterator(array: Array<T>): Iterator<T> = ArrayIterator(array)

internal fun <T> dotNetArrayIterable(array: Array<T>): Iterable<T> =
    if (array.size == 0) emptyList() else ArrayIterable(array)

internal fun dotNetErasedArrayIterator(array: Array<*>): Iterator<Any?> =
    ErasedArrayIterator(array)

internal fun dotNetErasedArrayIterable(array: Array<*>): Iterable<Any?> =
    if (array.size == 0) emptyList() else ErasedArrayIterable(array)

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

// `Array<*>` has no exact element token on CLR. This adapter retains the original System.Array
// carrier and observes it through the star-projected Any? read contract; it never materializes an
// object[] copy.
private class ErasedArrayIterator(private val array: Array<*>) : Iterator<Any?> {
    private var index: Int = 0

    override fun hasNext(): Boolean = index < array.size

    override fun next(): Any? {
        if (!hasNext()) throw NoSuchElementException()
        return array[index++]
    }
}

private class ErasedArrayIterable(private val array: Array<*>) : Iterable<Any?> {
    override fun iterator(): Iterator<Any?> = ErasedArrayIterator(array)
}
