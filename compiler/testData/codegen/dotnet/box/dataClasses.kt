// TARGET_BACKEND: DOTNET

data class Point(val x: Int, val label: String?, val rank: Int?)

fun box(): String {
    val first = Point(7, "p", 3)
    val equal = Point(7, "p", 3)
    val different = Point(8, "p", 3)
    if (first != equal || first == different || first == null) return "fail 1: equals"
    if (first.hashCode() != equal.hashCode()) return "fail 2: hashCode"
    if (first.toString() != "Point(x=7, label=p, rank=3)") return "fail 3: toString"
    if (first.component1() != 7 || first.component2() != "p" || first.component3() != 3) {
        return "fail 4: components"
    }
    val copy = first.copy(x = 9, rank = null)
    if (copy != Point(9, "p", null) || copy === first) return "fail 5: copy"
    return "OK"
}
