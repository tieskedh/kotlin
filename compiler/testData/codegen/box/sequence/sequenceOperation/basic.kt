// WITH_STDLIB

// CHECK_BYTECODE_TEXT
// 0 SequenceScope
fun box(): String {
    var result = ""
    val x = sequence {
        result += "OK"
        yield(1)
        result += "NOT OK"
        yield(2)
    }.first()

    return result
}
