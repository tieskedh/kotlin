// TARGET_BACKEND: DOTNET

private var evaluations = 0

private fun nextValue(): Int {
    evaluations = evaluations + 1
    return evaluations
}

private data class DefaultData(
    val first: Int = 1,
    val label: String = "v$first",
    val last: Int? = first + 2,
)

private class SecondaryDefaults {
    val text: String

    constructor(value: Int = 4, label: String = "s") {
        text = "$label:$value"
    }
}

private class ConstructorCollision(val value: Int = 7) {
    constructor(value: Int, mask: Int) : this(value + mask * 100)
}

private class GenericDefault<T>(val value: T, val label: String = "g")

private class Owner {
    class Nested(val value: Int = 5)

    inner class Inner(val value: Int = 6)
}

private class Delegating(val left: Int, val right: Int) {
    constructor(left: Int = 2) : this(left, left + 1)
}

private class Evaluated(
    val first: Int = nextValue(),
    val second: Int = nextValue(),
)

private class ManyDefaults(
    val p00: Int = 0,
    val p01: Int = 1,
    val p02: Int = 2,
    val p03: Int = 3,
    val p04: Int = 4,
    val p05: Int = 5,
    val p06: Int = 6,
    val p07: Int = 7,
    val p08: Int = 8,
    val p09: Int = 9,
    val p10: Int = 10,
    val p11: Int = 11,
    val p12: Int = 12,
    val p13: Int = 13,
    val p14: Int = 14,
    val p15: Int = 15,
    val p16: Int = 16,
    val p17: Int = 17,
    val p18: Int = 18,
    val p19: Int = 19,
    val p20: Int = 20,
    val p21: Int = 21,
    val p22: Int = 22,
    val p23: Int = 23,
    val p24: Int = 24,
    val p25: Int = 25,
    val p26: Int = 26,
    val p27: Int = 27,
    val p28: Int = 28,
    val p29: Int = 29,
    val p30: Int = 30,
    val p31: Int = 31,
    val p32: Int = 32,
) {
    fun edge(): Int = p00 * 100 + p31 * 10 + p32
}

private fun localDefault(seed: Int): Int {
    class Local(val value: Int = seed)
    return Local().value
}

fun box(): String {
    val all = DefaultData()
    if (all != DefaultData(1, "v1", 3)) return "fail 1: data defaults $all"
    val named = DefaultData(label = "x")
    if (named != DefaultData(1, "x", 3)) return "fail 2: named data defaults $named"
    if (named.copy(first = 8) != DefaultData(8, "x", 3)) return "fail 3: copy defaults"

    if (SecondaryDefaults().text != "s:4") return "fail 4: secondary defaults"
    if (SecondaryDefaults(label = "x").text != "x:4") return "fail 5: named secondary default"

    if (ConstructorCollision().value != 7) return "fail 6: primary default"
    if (ConstructorCollision(2, 3).value != 302) return "fail 7: marker collision"

    val generic = GenericDefault(9)
    if (generic.value != 9 || generic.label != "g") return "fail 8: generic constructor"

    val owner = Owner()
    if (Owner.Nested().value != 5 || owner.Inner().value != 6) return "fail 9: nested constructors"
    if (Delegating().left != 2 || Delegating().right != 3) return "fail 10: delegating constructor"
    if (localDefault(11) != 11) return "fail 11: local capture default"

    val evaluated = Evaluated(second = 9)
    if (evaluated.first != 1 || evaluated.second != 9 || evaluations != 1) {
        return "fail 12: evaluation order"
    }

    if (ManyDefaults().edge() != 342) return "fail 13: both masks default"
    if (ManyDefaults(p00 = 5, p32 = 9).edge() != 819) return "fail 14: partial second mask"

    return "OK"
}
