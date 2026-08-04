private fun fail(message: String): String = "fail: $message"

private class HostileSequence private constructor(
    private val text: String,
    private val start: Int,
    override val length: Int,
) : CharSequence {
    constructor(text: String) : this(text, 0, text.length)

    override fun get(index: Int): Char = text[start + index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        HostileSequence(text, start + startIndex, endIndex - startIndex)

    override fun toString(): String = throw IllegalStateException("CharSequence.toString must not be used")
}

private class OverflowSequence(override val length: Int) : CharSequence {
    override fun get(index: Int): Char =
        throw IllegalStateException("capacity overflow must be detected before reading the sequence")

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        throw IllegalStateException("capacity overflow must be detected before slicing the sequence")
}

private class NamedValue(private val text: String) {
    override fun toString(): String = text
}

private class RecordingAppendable : Appendable {
    private var text: String = ""

    override fun append(value: Char): Appendable {
        text += value
        return this
    }

    override fun append(value: CharSequence?): Appendable {
        val actualValue = value ?: "null"
        val endIndex = actualValue.length
        var index = 0
        while (index < endIndex) append(actualValue[index++])
        return this
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable {
        val actualValue = value ?: "null"
        var index = startIndex
        while (index < endIndex) append(actualValue[index++])
        return this
    }

    override fun toString(): String = text
}

private class CountingIterable(private val values: Array<String>) : Iterable<String> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<String> {
        iteratorCalls++
        return object : Iterator<String> {
            private var index = 0

            override fun hasNext(): Boolean = index < values.size

            override fun next(): String {
                nextCalls++
                return values[index++]
            }
        }
    }
}

@Suppress("DEPRECATION_ERROR")
fun box(): String {
    val empty = StringBuilder()
    if (empty.length != 0 || empty.toString() != "") return fail("empty constructor")
    val capacity = StringBuilder(32)
    if (capacity.capacity() < 32) return fail("capacity constructor")
    val fromString = StringBuilder("text")
    if (fromString.toString() != "text") return fail("String constructor")
    val fromSequence = StringBuilder(HostileSequence("sequence"))
    if (fromSequence.toString() != "sequence") return fail("CharSequence constructor")

    val identity = StringBuilder("a")
    val asAppendable: Appendable = identity
    if (asAppendable.append('b') !== identity) return fail("Appendable Char identity")
    if (asAppendable.append(HostileSequence("cde"), 1, 3) !== identity) {
        return fail("Appendable range identity")
    }
    if (identity.toString() != "abde") return fail("Appendable dispatch")
    val asCharSequence: CharSequence = identity
    if (asCharSequence.length != 4 || asCharSequence[2] != 'd' || asCharSequence.subSequence(1, 3) != "bd") {
        return fail("CharSequence dispatch")
    }
    val erasedBuilder: Any = identity
    val safelyClassifiedBuilder = erasedBuilder as? CharSequence
    if (erasedBuilder !is CharSequence || safelyClassifiedBuilder !== identity) {
        return fail("CharSequence classifier")
    }

    val initialOverflowContent = "aaaaaaaaaaaaaaaaaaaa"
    val overflowingSequence = OverflowSequence(Int.MAX_VALUE - initialOverflowContent.length + 1)
    val appendOverflow = StringBuilder(initialOverflowContent)
    try {
        appendOverflow.append(overflowingSequence)
        return fail("append overflow")
    } catch (_: Error) {
    }
    if (appendOverflow.toString() != initialOverflowContent) return fail("append overflow mutation")
    val insertOverflow = StringBuilder(initialOverflowContent)
    try {
        insertOverflow.insert(5, overflowingSequence)
        return fail("insert overflow")
    } catch (_: Error) {
    }
    if (insertOverflow.toString() != initialOverflowContent) return fail("insert overflow mutation")

    val selfAppend = StringBuilder("ab")
    selfAppend.append(selfAppend)
    if (selfAppend.toString() != "abab") return fail("self append")
    selfAppend.insertRange(1, selfAppend, 0, selfAppend.length)
    if (selfAppend.toString() != "aababbab") return fail("self insert")

    val rendered = StringBuilder()
    rendered.append(null as Any?)
        .append('|')
        .append(true)
        .append('|')
        .append(-12)
        .append('|')
        .append(2L)
        .append('|')
        .append(charArrayOf('x', 'y'))
        .append('|')
        .append(HostileSequence("hostile"))
    if (rendered.toString() != "null|true|-12|2|xy|hostile") {
        return fail("rendering ${rendered}")
    }
    rendered.insert(0, NamedValue("object"))
    rendered.insert(rendered.length, null as String?)
    if (rendered.toString() != "objectnull|true|-12|2|xy|hostilenull") {
        return fail("insert rendering ${rendered}")
    }

    val reversed = StringBuilder("my reverse test").reverse()
    if (reversed.toString() != "tset esrever ym") return fail("plain reverse ${reversed}")
    reversed.append('\uD800').append('\uDC00')
    reversed.insert(10, '\uDC01')
    reversed.insert(11, '\uD801')
    reversed.insert(0, "\uD802\uDC02")
    reversed.reverse()
    if (reversed.toString() != "\uD800\uDC00my re\uD801\uDC01verse test\uD802\uDC02") {
        return fail("surrogate reverse ${reversed}")
    }

    val edited = StringBuilder("012345")
    if (edited.indexOf("23") != 2 || edited.indexOf("", 99) != edited.length) {
        return fail("indexOf")
    }
    if (edited.lastIndexOf("23") != 2 || edited.lastIndexOf("", 99) != edited.length) {
        return fail("lastIndexOf")
    }
    if (edited.substring(2) != "2345" || edited.substring(1, 4) != "123") {
        return fail("substring")
    }
    edited[1] = 'a'
    edited.setRange(2, 4, "XYZ")
    edited.deleteAt(0)
    edited.deleteRange(4, 99)
    if (edited.toString() != "aXYZ") return fail("range edits ${edited}")
    edited.setLength(6)
    if (edited.length != 6 || edited[4] != '\u0000' || edited[5] != '\u0000') {
        return fail("setLength grow")
    }
    edited.setLength(4)
    val copied = CharArray(6)
    edited.toCharArray(copied, destinationOffset = 1, startIndex = 1, endIndex = 4)
    if (copied[1] != 'X' || copied[2] != 'Y' || copied[3] != 'Z') {
        return fail("toCharArray")
    }
    if (edited.clear() !== edited || edited.length != 0) return fail("clear")

    val unchanged = StringBuilder("safe")
    try {
        unchanged.insertRange(9, HostileSequence("x"), 0, 1)
        return fail("insertRange bound")
    } catch (_: IndexOutOfBoundsException) {
    }
    if (unchanged.toString() != "safe") return fail("failed edit mutation")

    val mixed = arrayOf<Any?>(null, HostileSequence("cs"), 'Q', NamedValue("obj")).asList()
    if (mixed.joinToString(separator = "|") != "null|cs|Q|obj") return fail("joinToString branches")

    val counting = CountingIterable(arrayOf("a", "b", "c"))
    val destination = RecordingAppendable()
    val returned = counting.joinTo(
        destination,
        separator = ":",
        prefix = "<",
        postfix = ">",
        limit = 1,
        truncated = "more",
    ) { value -> value + value }
    if (returned !== destination || destination.toString() != "<aa:more>") {
        return fail("joinTo result ${destination}")
    }
    if (counting.iteratorCalls != 1 || counting.nextCalls != 2) {
        return fail("joinTo traversal ${counting.iteratorCalls}/${counting.nextCalls}")
    }

    val callbackFailure = IllegalStateException("callback")
    try {
        arrayOf("x").asList().joinToString { throw callbackFailure }
        return fail("transform failure")
    } catch (caught: IllegalStateException) {
        if (caught !== callbackFailure) return fail("transform failure identity")
    }

    return "OK"
}
