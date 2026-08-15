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
        var index = 0
        while (index < actualValue.length) append(actualValue[index++])
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

private class NullableArrayRenderer<T>(private val values: Array<T?>) {
    fun render(): String = values.joinToString(prefix = "[", postfix = "]")
}

private fun <T> renderOpen(values: Array<out T>): String =
    values.joinToString(separator = "|", prefix = "<", postfix = ">")

private fun renderWidenedAny(values: Array<out Any?>): String =
    values.joinToString(separator = "|", prefix = "<", postfix = ">")

fun box(): String {
    val mixed = arrayOf<Any?>(null, HostileSequence("chars"), 'Q', NamedValue("object"))
    if (mixed.joinToString(separator = "|") != "null|chars|Q|object") return "fail 1: rendering"
    if (emptyArray<String>().joinToString(prefix = "<", postfix = ">") != "<>") return "fail 2: empty"
    if (arrayOf("a", "b").joinToString(":", "<", ">", 0, "more") != "<more>") {
        return "fail 3: zero limit"
    }
    if (arrayOf("a", "b").joinToString(":", "<", ">", 1, "more") != "<a:more>") {
        return "fail 4: truncation"
    }
    if (arrayOf(1, 2, 3).joinToString(transform = { value -> "v$value" }) != "v1, v2, v3") {
        return "fail 5: transform"
    }

    val destination = RecordingAppendable()
    val returned = arrayOf("a", "b").joinTo(
        destination,
        separator = ":",
        prefix = "<",
        postfix = ">",
    ) { value -> value + value }
    if (returned !== destination || destination.toString() != "<aa:bb>") return "fail 6: appendable"

    if (NullableArrayRenderer(arrayOf("left", null, "right")).render() != "[left, null, right]") {
        return "fail 7: erased owner nullable array"
    }
    if (renderOpen(arrayOf("open", "array")) != "<open|array>") return "fail 8: open array"

    val mutable = arrayOf("before", "old")
    val mutated = mutable.joinToString { value ->
        if (value == "before") mutable[1] = "after"
        value
    }
    if (mutated != "before, after") return "fail 9: live array view"

    val callbackFailure = IllegalStateException("callback")
    try {
        arrayOf("x").joinToString { throw callbackFailure }
        return "fail 10: transform did not fail"
    } catch (caught: IllegalStateException) {
        if (caught !== callbackFailure) return "fail 11: transform failure identity"
    }

    if (renderWidenedAny(arrayOf(1, 2)) != "<1|2>") return "fail 12: widened value fallback"
    if (NullableArrayRenderer(arrayOf<Int?>(1, null, 2)).render() != "[1, null, 2]") {
        return "fail 13: erased owner nullable value fallback"
    }
    var widenedVisits = 0
    val widenedValues: Array<out Any?> = arrayOf(3, 4)
    if (widenedValues.joinToString { value ->
            widenedVisits++
            "w$value"
        } != "w3, w4" || widenedVisits != 2
    ) {
        return "fail 14: widened transform"
    }

    return "OK"
}
