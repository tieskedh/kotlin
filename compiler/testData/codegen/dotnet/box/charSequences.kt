// Classified CharSequence carrier: raw System.String and Kotlin implementations share logical
// identity without wrapping, while arbitrary objects remain outside the classifier.

interface TaggedSequence : CharSequence

class TextWindow(
    private val text: String,
    private val start: Int,
    private val end: Int,
) : TaggedSequence {
    override val length: Int
        get() = end - start

    override fun get(index: Int): Char {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException()
        return text[start + index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex < 0 || endIndex < startIndex || endIndex > length) {
            throw IndexOutOfBoundsException()
        }
        return TextWindow(text, start + startIndex, start + endIndex)
    }
}

class SequenceBox<T : CharSequence>(val value: T)

class PretendsToBeSequence {
    override fun toString(): String = "pretends"
}

fun inspectSequence(value: CharSequence): String =
    "${value.length}:${value[0]}:${value.subSequence(1, value.length).length}"

fun <T : CharSequence> genericIdentity(value: T): T = value

fun <T : CharSequence> inspectGeneric(value: T): String =
    "${value.length}:${value[1]}"

fun box(): String {
    val string = "abcd"
    val widenedString: CharSequence = string
    if (widenedString !== string) return "fail 1: wrapped string"
    if (inspectSequence(widenedString) != "4:a:3") return "fail 2: string operations"
    val stringSlice = widenedString.subSequence(1, 3)
    if (stringSlice !is String || stringSlice != "bc") return "fail 3: string subsequence"

    val window = TextWindow("wxyz", 0, 4)
    val tagged: TaggedSequence = window
    val widenedWindow: CharSequence = tagged
    if (widenedWindow !== window) return "fail 4: implementation identity"
    if (inspectSequence(widenedWindow) != "4:w:3") return "fail 5: implementation operations"
    val windowSlice = widenedWindow.subSequence(1, 3)
    if (windowSlice !is TextWindow || windowSlice.length != 2 || windowSlice[0] != 'x' || windowSlice[1] != 'y') {
        return "fail 6: implementation subsequence"
    }

    val stringAsAny: Any = string
    val windowAsAny: Any = window
    val hostileAsAny: Any = PretendsToBeSequence()
    val numberAsAny: Any = 42
    if (stringAsAny !is CharSequence || windowAsAny !is CharSequence) return "fail 7: positive tests"
    if (hostileAsAny is CharSequence || numberAsAny is CharSequence) return "fail 8: false admission"
    if (null is CharSequence || null !is CharSequence?) return "fail 9: nullable tests"

    val safeString = stringAsAny as? CharSequence
    val safeWindow = windowAsAny as? CharSequence
    val safeHostile = hostileAsAny as? CharSequence
    if (safeString !== stringAsAny || safeWindow !== windowAsAny || safeHostile != null) {
        return "fail 10: safe casts"
    }
    val checkedString = stringAsAny as CharSequence
    val checkedWindow = windowAsAny as CharSequence
    if (checkedString !== stringAsAny || checkedWindow !== windowAsAny) {
        return "fail 11: checked cast identity"
    }
    try {
        hostileAsAny as CharSequence
        return "fail 12: invalid cast succeeded"
    } catch (_: ClassCastException) {
    }

    val genericString = genericIdentity(string)
    val genericWindow = genericIdentity(window)
    if (genericString !== stringAsAny || genericWindow !== windowAsAny) {
        return "fail 13: generic identity"
    }
    if (inspectGeneric(string) != "4:b" || inspectGeneric(window) != "4:x") {
        return "fail 14: generic operations"
    }
    if (SequenceBox(string).value !== stringAsAny ||
        SequenceBox(window).value !== windowAsAny
    ) {
        return "fail 15: generic class storage"
    }

    try {
        widenedString.subSequence(-1, 1)
        return "fail 16: negative subsequence start"
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        widenedString.subSequence(3, 2)
        return "fail 17: reversed subsequence range"
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        widenedString.subSequence(0, 5)
        return "fail 18: subsequence end overflow"
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        widenedString[4]
        return "fail 19: string get overflow"
    } catch (_: IndexOutOfBoundsException) {
    }

    return "OK"
}
