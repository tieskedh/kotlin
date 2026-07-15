// TARGET_BACKEND: DOTNET

private fun combine(first: Int = 1, middle: String = "m", last: Int? = 3): String =
    "$first:$middle:$last"

private class MemberDefaults(private val seed: Int) {
    fun sum(first: Int = seed, second: Int = first + 1): Int = first + second
}

fun box(): String {
    if (combine() != "1:m:3") return "fail 1: all defaults"
    if (combine(9) != "9:m:3") return "fail 2: trailing defaults"
    if (combine(middle = "x", last = null) != "1:x:null") return "fail 3: named defaults"

    val member = MemberDefaults(4)
    if (member.sum() != 9) return "fail 4: receiver default"
    if (member.sum(10) != 21) return "fail 5: earlier parameter default"
    if (member.sum(second = 20) != 24) return "fail 6: partial member defaults"
    return "OK"
}
