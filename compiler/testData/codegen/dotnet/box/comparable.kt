private class Rank(private val value: Int) : Comparable<Rank> {
    override fun compareTo(other: Rank): Int = value - other.value
}

private class AnyComparable(private val value: String) : Comparable<Any> {
    override fun compareTo(other: Any): Int = value.compareTo(other.toString())
}

private class Plain

private interface Ranked : Comparable<Ranked> {
    val rank: Int
}

private class RankedImpl(override val rank: Int) : Ranked {
    override fun compareTo(other: Ranked): Int = rank - other.rank
}

private fun compareRanks(left: Comparable<Rank>, right: Rank): Int = left.compareTo(right)

private fun compareRanked(left: Comparable<Ranked>, right: Ranked): Int = left.compareTo(right)

private fun <T : Comparable<T>> genericCompare(left: T, right: T): Int = left.compareTo(right)

private fun isComparable(value: Any): Boolean = value is Comparable<*>

private fun safeComparable(value: Any): Comparable<*>? = value as? Comparable<*>

private fun comparableAsAny(value: Comparable<*>): Any = value

private fun inferredComparableArray(selectInt: Boolean): Array<*> =
    if (selectInt) arrayOf(1) else arrayOf("two")

@Suppress("UNCHECKED_CAST")
private fun delayedWrongArgument(): Boolean {
    val wrong = "text" as Comparable<Int>
    return try {
        wrong.compareTo(1)
        false
    } catch (_: ClassCastException) {
        true
    }
}

@Suppress("UNCHECKED_CAST")
private fun nullStringArgumentFails(): Boolean {
    val wrong = "text" as Comparable<String?>
    return try {
        wrong.compareTo(null)
        false
    } catch (_: NullPointerException) {
        true
    }
}

fun box(): String {
    val low = Rank(2)
    val high = Rank(5)
    if (low.compareTo(high) >= 0) return "fail 1: direct implementation"
    if (compareRanks(high, low) <= 0) return "fail 2: canonical implementation"
    if (genericCompare(low, high) >= 0) return "fail 3: recursive generic bound"
    if (compareRanked(RankedImpl(3), RankedImpl(2)) <= 0) return "fail 3a: inherited interface slot"

    val contravariant: Comparable<String> = AnyComparable("m")
    if (contravariant.compareTo("z") >= 0) return "fail 4: contravariant view"

    if (genericCompare(false, true) >= 0) return "fail 5: boxed Boolean carrier"
    if (genericCompare(1.toByte(), 2.toByte()) >= 0) return "fail 6: boxed Byte carrier"
    if (genericCompare(1.toShort(), 2.toShort()) >= 0) return "fail 7: boxed Short carrier"
    if (genericCompare(3, 2) <= 0) return "fail 8: boxed Int carrier"
    if (genericCompare(3L, 2L) <= 0) return "fail 9: boxed Long carrier"
    if (genericCompare('a', 'b') >= 0) return "fail 10: boxed Char carrier"
    if (genericCompare("ä", "z") <= 0) return "fail 11: ordinal String order"
    if (genericCompare(Double.NaN, Double.POSITIVE_INFINITY) <= 0) {
        return "fail 12: Double NaN order"
    }
    if (genericCompare(Float.NaN, Float.POSITIVE_INFINITY) <= 0) {
        return "fail 13: Float NaN order"
    }
    if (genericCompare(-0.0, 0.0) >= 0) return "fail 14: Double signed zero"
    if (genericCompare(-0.0f, 0.0f) >= 0) return "fail 15: Float signed zero"

    val builtIn: Any = 7
    val custom: Any = low
    if (!isComparable(builtIn)) return "fail 16: built-in type test"
    if (!isComparable(custom)) return "fail 17: custom type test"
    if (isComparable(Plain())) return "fail 18: false type test"
    if (safeComparable(builtIn) == null) return "fail 18a: safe cast success"
    if (safeComparable(Plain()) != null) return "fail 18b: safe cast failure"
    if (comparableAsAny(42) != 42) return "fail 19: scalar canonical widening"
    if (comparableAsAny("same") != "same") return "fail 20: String canonical widening"
    if ((inferredComparableArray(true) as Array<Int>)[0] != 1) {
        return "fail 21: inferred scalar array carrier"
    }
    if ((inferredComparableArray(false) as Array<String>)[0] != "two") {
        return "fail 22: inferred reference array carrier"
    }
    if (!delayedWrongArgument()) return "fail 23: delayed wrong argument"
    if (!nullStringArgumentFails()) return "fail 24: null String argument"

    return "OK"
}
