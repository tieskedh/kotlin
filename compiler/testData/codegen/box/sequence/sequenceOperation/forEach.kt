// WITH_STDLIB

fun box(): String {
    var result = ""
    sequence {
        yield("O")
        yield("K")
    }.forEach { result += it }
    return result
}
