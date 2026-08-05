// TARGET_BACKEND: DOTNET

private var trace: String = ""

private fun marked(value: Int): Int {
    trace = trace + "e" + value + ";"
    return value
}

private fun spreadValues(first: Int, second: Int): IntArray {
    trace = trace + "s" + first + second + ";"
    return intArrayOf(first, second)
}

private fun failingSpread(): IntArray {
    trace = trace + "throw;"
    throw Exception()
}

private fun sum(vararg values: Int): Int {
    var result = 0
    for (value in values) result = result + value
    return result
}

private fun longs(vararg values: Long): Long = values[0] + values[1]

private fun doubles(vararg values: Double): Double = values[0] + values[1]

private fun booleans(vararg values: Boolean): Boolean = values[0] && !values[1]

private fun chars(vararg values: Char): Int = values[0].code + values[1].code

private fun strings(vararg values: String?): String =
    (values[0] ?: "null") + ":" + (values[1] ?: "null")

private fun objects(vararg values: Any?): String =
    values[0].toString() + ":" + values[1].toString() + ":" + values[2].toString()

private fun <T> genericCount(vararg values: T): Int = values.size

private fun <T> genericFirst(vararg values: T): T = values[0]

private fun <T> genericForward(vararg values: T): T = genericFirst(*values)

private class Item(val value: Int)

private fun items(vararg values: Item?): Int =
    (values[0]?.value ?: 0) + (values[1]?.value ?: 0)

private class GenericItem<T>(val value: T)

private fun genericItems(vararg values: GenericItem<String>): String =
    values[0].value + values[1].value

private fun positioned(vararg values: Int, suffix: Int): Int = sum(*values) + suffix

private fun overwrite(vararg values: Int): Int {
    values[0] = 99
    return values[0]
}

private fun aliasedStrings(vararg values: String?): String {
    val alias = values
    return strings(*alias)
}

private fun capturedValues(vararg values: Int): Int {
    fun local(): Int = values[0] + values[1]
    return local()
}

private fun configured(vararg values: Int = intArrayOf(7, 8)): Int = sum(*values)

private fun defaultBeforeVararg(prefix: Int = 10, vararg values: Int): Int = prefix + sum(*values)

private class VarargOwner {
    fun member(prefix: String, vararg values: String): String =
        prefix + values[0] + values[1]
}

private fun VarargOwner.extension(prefix: String, vararg values: Int): Int =
    3 + sum(*values)

private class VarargConstructor(vararg values: Char) {
    val total: Int = chars(*values)
}

private interface VarargInterface {
    fun collect(vararg values: Long): Long
}

private class VarargInterfaceImpl : VarargInterface {
    override fun collect(vararg values: Long): Long = longs(*values)
}

fun box(): String {
    if (sum() != 0) return "fail 1: empty"
    if (sum(1, 2, 3) != 6) return "fail 2: literal"
    if (sum(*intArrayOf(1), 2, *intArrayOf(3)) != 6) return "fail 2a: multiple spreads"
    if (sum(1, *intArrayOf(), 2) != 3) return "fail 2b: empty spread"

    trace = ""
    if (sum(marked(1), *spreadValues(2, 3), marked(4)) != 10) return "fail 3: mixed"
    if (trace != "e1;s23;e4;") return "fail 4: order $trace"

    trace = ""
    try {
        sum(marked(1), *failingSpread(), marked(2))
        return "fail 5: exception"
    } catch (_: Exception) {
        if (trace != "e1;throw;") return "fail 6: exception order $trace"
    }

    val original = intArrayOf(5, 6)
    if (overwrite(*original) != 99 || original[0] != 5) return "fail 7: spread alias"

    if (longs(*longArrayOf(4L), 5L) != 9L) return "fail 8: long"
    if (doubles(1.5, *doubleArrayOf(2.25)) != 3.75) return "fail 9: double"
    if (!booleans(*booleanArrayOf(true), false)) return "fail 10: boolean"
    if (chars('A', *charArrayOf('B')) != 131) return "fail 11: char"

    val words = arrayOf<String?>("left", null)
    if (strings(*words) != "left:null") return "fail 12: nullable references"
    if (objects(1, null, "value") != "1:null:value") return "fail 13: objects"
    if (items(Item(4), *arrayOf<Item?>(null)) != 4) return "fail 14: user classes"
    if (genericItems(GenericItem("a"), GenericItem("b")) != "ab") return "fail 14a: generic classes"
    if (positioned(1, 2, suffix = 3) != 6) return "fail 14b: non-final vararg"

    if (aliasedStrings("left", null) != "left:null") return "fail 15: alias"
    if (capturedValues(4, 5) != 9) return "fail 16: capture"
    if (configured() != 15 || configured(2, 3) != 5) return "fail 17: vararg default"
    if (defaultBeforeVararg(values = intArrayOf(1, 2)) != 13) return "fail 18: default before"
    if (defaultBeforeVararg() != 10) return "fail 19: defaults and empty vararg"

    val owner = VarargOwner()
    if (owner.member("p", "a", *arrayOf("b")) != "pab") return "fail 20: member"
    if (owner.extension("abc", 4, *intArrayOf(5)) != 12) return "fail 21: extension"
    if (VarargConstructor('A', *charArrayOf('B')).total != 131) return "fail 22: constructor"

    val interfaceView: VarargInterface = VarargInterfaceImpl()
    if (interfaceView.collect(6L, *longArrayOf(7L)) != 13L) return "fail 23: interface"

    val spreadInts = intArrayOf(*intArrayOf(1, 2), 3)
    if (spreadInts.size != 3 || spreadInts[2] != 3) return "fail 24: primitive arrayOf spread"
    val spreadStrings = arrayOf("a", *arrayOf("b", "c"))
    if (spreadStrings.size != 3 || spreadStrings[2] != "c") return "fail 25: generic arrayOf spread"

    fun local(vararg values: Int): Int = sum(*values)
    if (local(8, *intArrayOf(9)) != 17) return "fail 26: local"
    if (genericCount<Int>() != 0) return "fail 27: empty generic vararg"
    if (genericFirst(40, 41) != 40) return "fail 28: value generic vararg"
    if (genericForward("left", *arrayOf("right")) != "left") return "fail 29: reference generic spread"
    if (genericForward<String?>(null, "value") != null) return "fail 30: nullable generic vararg"
    return "OK"
}
