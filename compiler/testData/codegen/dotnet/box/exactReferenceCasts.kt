// Physically exact reference casts are a prerequisite of reified inline operations. Keep this
// matrix independent of Kotlin-owned generic-class identity: those casts remain rejected because
// their logical arguments are erased while their current CLR carrier is a closed GenericInstance.

interface ExactMarker {
    val marker: String
}

open class ExactBase(val value: Int)

class ExactDerived(value: Int) : ExactBase(value), ExactMarker {
    override val marker: String
        get() = "derived"
}

class ExactOther

var exactCastEvaluationCount: Int = 0

fun countedExactCastOperand(value: Any?): Any? {
    exactCastEvaluationCount = exactCastEvaluationCount + 1
    return value
}

fun box(): String {
    val derived = ExactDerived(7)
    val derivedAsAny: Any = derived
    val otherAsAny: Any = ExactOther()

    val asDerived = derivedAsAny as ExactDerived
    if (asDerived !== derived || asDerived.value != 7) return "fail 1: exact class cast"

    val asBase = derivedAsAny as ExactBase
    if (asBase !== derived || asBase.value != 7) return "fail 2: base cast"

    val asMarker = derivedAsAny as ExactMarker
    if (asMarker !== derived || asMarker.marker != "derived") return "fail 3: interface cast"

    if (otherAsAny as? ExactDerived != null) return "fail 4: safe class mismatch"
    try {
        otherAsAny as ExactDerived
        return "fail 5: checked class mismatch did not throw"
    } catch (_: ClassCastException) {
    }

    val nullAsAny: Any? = null
    if (nullAsAny as? ExactDerived != null) return "fail 6: safe null"
    if (nullAsAny as ExactDerived? != null) return "fail 7: nullable checked null"
    try {
        nullAsAny as ExactDerived
        return "fail 8: non-null checked cast accepted null"
    } catch (_: NullPointerException) {
    }

    val textAsAny: Any = "text"
    if ((textAsAny as String) != "text") return "fail 9: string cast"
    if (otherAsAny as? String != null) return "fail 10: safe string mismatch"
    if ((derivedAsAny as Any) !== derived) return "fail 11: Any cast changed identity"
    try {
        nullAsAny as Any
        return "fail 12: non-null Any cast accepted null"
    } catch (_: NullPointerException) {
    }

    val ints = intArrayOf(1, 2, 3)
    val intsAsAny: Any = ints
    val castInts = intsAsAny as IntArray
    if (castInts !== ints || castInts.size != 3 || castInts[1] != 2) {
        return "fail 13: primitive-array cast"
    }
    if (longArrayOf(1L) as Any as? IntArray != null) {
        return "fail 14: safe primitive-array mismatch"
    }

    val strings = arrayOf("a", "b")
    val stringsAsAny: Any = strings
    val castStrings = stringsAsAny as Array<String>
    if (castStrings !== strings || castStrings.size != 2 || castStrings[1] != "b") {
        return "fail 15: generic-array cast"
    }
    if (arrayOf(1) as Any as? Array<String> != null) {
        return "fail 16: safe generic-array mismatch"
    }

    exactCastEvaluationCount = 0
    if (countedExactCastOperand(derived) as? ExactDerived !== derived || exactCastEvaluationCount != 1) {
        return "fail 17: safe cast evaluated operand more than once"
    }
    exactCastEvaluationCount = 0
    if (countedExactCastOperand(derived) as ExactBase !== derived || exactCastEvaluationCount != 1) {
        return "fail 18: checked cast evaluated operand more than once"
    }

    return "OK"
}
