/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.text

public actual interface Appendable {
    @IgnorableReturnValue
    public actual fun append(value: Char): Appendable

    @IgnorableReturnValue
    public actual fun append(value: CharSequence?): Appendable

    @IgnorableReturnValue
    public actual fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable
}

/** Kotlin-owned builder identity over one private CLR StringBuilder storage object. */
public actual class StringBuilder private constructor(
    private val storage: Any,
    @Suppress("UNUSED_PARAMETER") marker: Int,
) : Appendable, CharSequence {
    public actual constructor() : this(dotNetStringBuilderCreate(), 0)

    public actual constructor(capacity: Int) : this(dotNetStringBuilderCreate(capacity), 0)

    public actual constructor(content: String) : this(dotNetStringBuilderCreate(content), 0)

    public actual constructor(content: CharSequence) : this() {
        append(content)
    }

    actual override val length: Int
        get() = dotNetStringBuilderLength(storage)

    actual override fun get(index: Int): Char {
        AbstractList.checkElementIndex(index, length)
        return dotNetStringBuilderGet(storage, index)
    }

    actual override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        substring(startIndex, endIndex)

    @IgnorableReturnValue
    actual override fun append(value: Char): StringBuilder {
        dotNetStringBuilderAppend(storage, value)
        return this
    }

    @IgnorableReturnValue
    actual override fun append(value: CharSequence?): StringBuilder {
        val actualValue = value ?: "null"
        val endIndex = actualValue.length
        ensureExtraCapacity(endIndex)
        if (actualValue is String) {
            dotNetStringBuilderAppend(storage, actualValue)
        } else {
            // Match Common/Native: freeze the requested range before mutating this builder.
            // In particular, `builder.append(builder)` must append one snapshot-length copy
            // instead of observing its own growing length forever.
            var index = 0
            while (index < endIndex) append(actualValue[index++])
        }
        return this
    }

    @IgnorableReturnValue
    actual override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): StringBuilder =
        appendRange(value ?: "null", startIndex, endIndex)

    @IgnorableReturnValue
    public actual fun reverse(): StringBuilder {
        if (length < 2) return this

        // Apache Harmony/JVM-compatible reversal: exchange UTF-16 code points while preserving
        // the high/low order of surrogate pairs. The storage remains private and is observed only
        // through exact character primitives.
        var end = length - 1
        var front = 0
        var frontLeadingChar = get(0)
        var endTrailingChar = get(end)
        var allowFrontSurrogate = true
        var allowEndSurrogate = true
        while (front < length / 2) {
            val frontTrailingChar = get(front + 1)
            val endLeadingChar = get(end - 1)
            val surrogateAtFront = allowFrontSurrogate &&
                    frontTrailingChar >= '\uDC00' && frontTrailingChar <= '\uDFFF' &&
                    frontLeadingChar >= '\uD800' && frontLeadingChar <= '\uDBFF'
            if (surrogateAtFront && length < 3) return this
            val surrogateAtEnd = allowEndSurrogate &&
                    endTrailingChar >= '\uDC00' && endTrailingChar <= '\uDFFF' &&
                    endLeadingChar >= '\uD800' && endLeadingChar <= '\uDBFF'
            allowFrontSurrogate = true
            allowEndSurrogate = true
            when {
                surrogateAtFront && surrogateAtEnd -> {
                    set(end, frontTrailingChar)
                    set(end - 1, frontLeadingChar)
                    set(front, endLeadingChar)
                    set(front + 1, endTrailingChar)
                    frontLeadingChar = get(front + 2)
                    endTrailingChar = get(end - 2)
                    front++
                    end--
                }
                !surrogateAtFront && !surrogateAtEnd -> {
                    set(end, frontLeadingChar)
                    set(front, endTrailingChar)
                    frontLeadingChar = frontTrailingChar
                    endTrailingChar = endLeadingChar
                }
                surrogateAtFront -> {
                    set(end, frontTrailingChar)
                    set(front, endTrailingChar)
                    endTrailingChar = endLeadingChar
                    allowFrontSurrogate = false
                }
                else -> {
                    set(end, frontLeadingChar)
                    set(front, endLeadingChar)
                    frontLeadingChar = frontTrailingChar
                    allowEndSurrogate = false
                }
            }
            front++
            end--
        }
        if (length % 2 == 1 && (!allowEndSurrogate || !allowFrontSurrogate)) {
            set(end, if (allowFrontSurrogate) endTrailingChar else frontLeadingChar)
        }
        return this
    }

    @IgnorableReturnValue
    public actual fun append(value: Any?): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public actual fun append(value: Boolean): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public fun append(value: Byte): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public fun append(value: Short): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public actual fun append(value: Int): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public actual fun append(value: Long): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public actual fun append(value: Float): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public actual fun append(value: Double): StringBuilder = append(value.toString())

    @IgnorableReturnValue
    public actual fun append(value: CharArray): StringBuilder = appendRange(value, 0, value.size)

    @IgnorableReturnValue
    public actual fun append(value: String?): StringBuilder {
        val actualValue = value ?: "null"
        ensureExtraCapacity(actualValue.length)
        dotNetStringBuilderAppend(storage, actualValue)
        return this
    }

    public actual fun capacity(): Int = dotNetStringBuilderCapacity(storage)

    public actual fun ensureCapacity(minimumCapacity: Int) {
        if (minimumCapacity > capacity()) dotNetStringBuilderEnsureCapacity(storage, minimumCapacity)
    }

    public actual fun indexOf(string: String): Int = indexOf(string, 0)

    public actual fun indexOf(string: String, startIndex: Int): Int {
        val fromIndex = if (startIndex < 0) 0 else startIndex
        if (string.length == 0) return if (fromIndex > length) length else fromIndex
        val lastStart = length - string.length
        var candidate = fromIndex
        while (candidate <= lastStart) {
            if (matchesAt(string, candidate)) return candidate
            candidate++
        }
        return -1
    }

    public actual fun lastIndexOf(string: String): Int = lastIndexOf(string, length)

    public actual fun lastIndexOf(string: String, startIndex: Int): Int {
        if (startIndex < 0) return -1
        if (string.length == 0) return if (startIndex > length) length else startIndex
        var candidate = if (startIndex < length - string.length) startIndex else length - string.length
        while (candidate >= 0) {
            if (matchesAt(string, candidate)) return candidate
            candidate--
        }
        return -1
    }

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Boolean): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public fun insert(index: Int, value: Byte): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public fun insert(index: Int, value: Short): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Int): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Long): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Float): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Double): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Char): StringBuilder {
        AbstractList.checkPositionIndex(index, length)
        ensureExtraCapacity(1)
        dotNetStringBuilderInsert(storage, index, value)
        return this
    }

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: CharArray): StringBuilder =
        insertRange(index, value, 0, value.size)

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: CharSequence?): StringBuilder {
        val actualValue = value ?: "null"
        return insertRange(index, actualValue, 0, actualValue.length)
    }

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: Any?): StringBuilder = insert(index, value.toString())

    @IgnorableReturnValue
    public actual fun insert(index: Int, value: String?): StringBuilder {
        AbstractList.checkPositionIndex(index, length)
        val actualValue = value ?: "null"
        ensureExtraCapacity(actualValue.length)
        dotNetStringBuilderInsert(storage, index, actualValue)
        return this
    }

    public actual fun setLength(newLength: Int) {
        if (newLength < 0) throw IllegalArgumentException("Negative new length: $newLength.")
        dotNetStringBuilderSetLength(storage, newLength)
    }

    public actual fun substring(startIndex: Int): String = substring(startIndex, length)

    public actual fun substring(startIndex: Int, endIndex: Int): String {
        AbstractList.checkBoundsIndexes(startIndex, endIndex, length)
        return dotNetStringBuilderSubstring(storage, startIndex, endIndex - startIndex)
    }

    public actual fun trimToSize() {
        // Common explicitly permits this operation to have no observable effect.
    }

    override fun toString(): String = dotNetStringBuilderToString(storage)

    public operator fun set(index: Int, value: Char) {
        AbstractList.checkElementIndex(index, length)
        dotNetStringBuilderSet(storage, index, value)
    }

    @IgnorableReturnValue
    public fun setRange(startIndex: Int, endIndex: Int, value: String): StringBuilder {
        checkReplaceRange(startIndex, endIndex)
        val actualEndIndex = if (endIndex > length) length else endIndex
        ensureExtraCapacity(value.length - (actualEndIndex - startIndex))
        if (actualEndIndex > startIndex) {
            dotNetStringBuilderRemove(storage, startIndex, actualEndIndex - startIndex)
        }
        if (value.length > 0) dotNetStringBuilderInsert(storage, startIndex, value)
        return this
    }

    @IgnorableReturnValue
    public fun deleteAt(index: Int): StringBuilder {
        AbstractList.checkElementIndex(index, length)
        dotNetStringBuilderRemove(storage, index, 1)
        return this
    }

    @IgnorableReturnValue
    public fun deleteRange(startIndex: Int, endIndex: Int): StringBuilder {
        checkReplaceRange(startIndex, endIndex)
        val actualEndIndex = if (endIndex > length) length else endIndex
        if (actualEndIndex > startIndex) {
            dotNetStringBuilderRemove(storage, startIndex, actualEndIndex - startIndex)
        }
        return this
    }

    public fun toCharArray(
        destination: CharArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = length,
    ) {
        AbstractList.checkBoundsIndexes(startIndex, endIndex, length)
        AbstractList.checkBoundsIndexes(destinationOffset, destinationOffset + endIndex - startIndex, destination.size)
        var fromIndex = startIndex
        var toIndex = destinationOffset
        while (fromIndex < endIndex) destination[toIndex++] = get(fromIndex++)
    }

    @IgnorableReturnValue
    public fun appendRange(value: CharArray, startIndex: Int, endIndex: Int): StringBuilder {
        AbstractList.checkBoundsIndexes(startIndex, endIndex, value.size)
        ensureExtraCapacity(endIndex - startIndex)
        var index = startIndex
        while (index < endIndex) append(value[index++])
        return this
    }

    @IgnorableReturnValue
    public fun appendRange(value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder {
        AbstractList.checkBoundsIndexes(startIndex, endIndex, value.length)
        ensureExtraCapacity(endIndex - startIndex)
        if (value is String) {
            if (startIndex == 0 && endIndex == value.length) return append(value)
        }
        var index = startIndex
        while (index < endIndex) append(value[index++])
        return this
    }

    @IgnorableReturnValue
    public fun insertRange(index: Int, value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder {
        AbstractList.checkPositionIndex(index, length)
        AbstractList.checkBoundsIndexes(startIndex, endIndex, value.length)
        ensureExtraCapacity(endIndex - startIndex)
        insertChars(index, snapshot(value, startIndex, endIndex))
        return this
    }

    @IgnorableReturnValue
    public fun insertRange(index: Int, value: CharArray, startIndex: Int, endIndex: Int): StringBuilder {
        AbstractList.checkPositionIndex(index, length)
        AbstractList.checkBoundsIndexes(startIndex, endIndex, value.size)
        ensureExtraCapacity(endIndex - startIndex)
        val chars = CharArray(endIndex - startIndex)
        var fromIndex = startIndex
        var toIndex = 0
        while (fromIndex < endIndex) chars[toIndex++] = value[fromIndex++]
        insertChars(index, chars)
        return this
    }

    private fun matchesAt(string: String, startIndex: Int): Boolean {
        var index = 0
        while (index < string.length) {
            if (get(startIndex + index) != string[index]) return false
            index++
        }
        return true
    }

    private fun ensureExtraCapacity(additionalLength: Int) {
        val minimumCapacity = length.toLong() + additionalLength.toLong()
        if (minimumCapacity < 0L || minimumCapacity > 2147483647L) {
            dotNetStringBuilderThrowCapacityOverflow()
        }
        if (minimumCapacity > capacity().toLong()) {
            dotNetStringBuilderEnsureCapacity(storage, minimumCapacity.toInt())
        }
    }

    private fun snapshot(value: CharSequence, startIndex: Int, endIndex: Int): CharArray {
        val chars = CharArray(endIndex - startIndex)
        var fromIndex = startIndex
        var toIndex = 0
        while (fromIndex < endIndex) chars[toIndex++] = value[fromIndex++]
        return chars
    }

    private fun insertChars(index: Int, chars: CharArray) {
        if (chars.size == 0) return
        val oldLength = length
        setLength(oldLength + chars.size)
        var sourceIndex = oldLength - 1
        while (sourceIndex >= index) {
            set(sourceIndex + chars.size, get(sourceIndex))
            sourceIndex--
        }
        var charIndex = 0
        while (charIndex < chars.size) {
            set(index + charIndex, chars[charIndex])
            charIndex++
        }
    }

    private fun checkReplaceRange(startIndex: Int, endIndex: Int) {
        if (startIndex < 0 || startIndex > length) {
            throw IndexOutOfBoundsException("startIndex: $startIndex, length: $length")
        }
        if (startIndex > endIndex) {
            throw IllegalArgumentException("startIndex($startIndex) > endIndex($endIndex)")
        }
    }

}

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.append(value: Byte): StringBuilder = this.append(value)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.append(value: Short): StringBuilder = this.append(value)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.insert(index: Int, value: Byte): StringBuilder = this.insert(index, value)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.insert(index: Int, value: Short): StringBuilder = this.insert(index, value)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendLine(value: Byte): StringBuilder = append(value).appendLine()

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendLine(value: Short): StringBuilder = append(value).appendLine()

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendLine(value: Int): StringBuilder = append(value).appendLine()

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendLine(value: Long): StringBuilder = append(value).appendLine()

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendLine(value: Float): StringBuilder = append(value).appendLine()

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendLine(value: Double): StringBuilder = append(value).appendLine()

@IgnorableReturnValue
public actual fun StringBuilder.clear(): StringBuilder {
    setLength(0)
    return this
}

@kotlin.internal.InlineOnly
public actual inline operator fun StringBuilder.set(index: Int, value: Char): Unit = this.set(index, value)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.setRange(startIndex: Int, endIndex: Int, value: String): StringBuilder =
    this.setRange(startIndex, endIndex, value)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.deleteAt(index: Int): StringBuilder = this.deleteAt(index)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.deleteRange(startIndex: Int, endIndex: Int): StringBuilder =
    this.deleteRange(startIndex, endIndex)

@kotlin.internal.InlineOnly
public actual inline fun StringBuilder.toCharArray(
    destination: CharArray,
    destinationOffset: Int,
    startIndex: Int,
    endIndex: Int,
): Unit = this.toCharArray(destination, destinationOffset, startIndex, endIndex)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendRange(
    value: CharArray,
    startIndex: Int,
    endIndex: Int,
): StringBuilder = this.appendRange(value, startIndex, endIndex)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.appendRange(
    value: CharSequence,
    startIndex: Int,
    endIndex: Int,
): StringBuilder = this.appendRange(value, startIndex, endIndex)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.insertRange(
    index: Int,
    value: CharArray,
    startIndex: Int,
    endIndex: Int,
): StringBuilder = this.insertRange(index, value, startIndex, endIndex)

@kotlin.internal.InlineOnly
@IgnorableReturnValue
public actual inline fun StringBuilder.insertRange(
    index: Int,
    value: CharSequence,
    startIndex: Int,
    endIndex: Int,
): StringBuilder = this.insertRange(index, value, startIndex, endIndex)

// Resolution-only private storage operations. The .NET backend emits direct BCL member calls and
// suppresses these declarations, keeping System.Text.StringBuilder out of the public Kotlin ABI.
private external fun dotNetStringBuilderCreate(): Any
private external fun dotNetStringBuilderCreate(capacity: Int): Any
private external fun dotNetStringBuilderCreate(content: String): Any
private external fun dotNetStringBuilderLength(storage: Any): Int
private external fun dotNetStringBuilderGet(storage: Any, index: Int): Char
private external fun dotNetStringBuilderSet(storage: Any, index: Int, value: Char)
private external fun dotNetStringBuilderAppend(storage: Any, value: Char)
private external fun dotNetStringBuilderAppend(storage: Any, value: String)
private external fun dotNetStringBuilderInsert(storage: Any, index: Int, value: Char)
private external fun dotNetStringBuilderInsert(storage: Any, index: Int, value: String)
private external fun dotNetStringBuilderSetLength(storage: Any, newLength: Int)
private external fun dotNetStringBuilderCapacity(storage: Any): Int
private external fun dotNetStringBuilderEnsureCapacity(storage: Any, minimumCapacity: Int)
private external fun dotNetStringBuilderRemove(storage: Any, startIndex: Int, length: Int)
private external fun dotNetStringBuilderSubstring(storage: Any, startIndex: Int, length: Int): String
private external fun dotNetStringBuilderToString(storage: Any): String
private external fun dotNetStringBuilderThrowCapacityOverflow(): Nothing
