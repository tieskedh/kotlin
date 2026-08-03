package test

annotation class Marker
annotation class Other

fun box(): String {
    val first: Annotation = Marker()
    val second = Marker()
    if (first !is Marker) return "FAIL: marker type"
    if (first != second || second != first) return "FAIL: equality"
    if (first == Other()) return "FAIL: foreign equality"
    if (first.hashCode() != 0 || second.hashCode() != 0) return "FAIL: hash"
    if (first.toString() != "@test.Marker()") return "FAIL: string ${first}"
    return "OK"
}
