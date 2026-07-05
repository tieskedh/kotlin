package test

import kotlin.io.println

fun stringLocal(): String {
    val value = "OK"
    return value
}

fun intLocal(): Int {
    val value = 41
    var result = value
    result = intIdentity(42)
    return result
}

fun intIdentity(value: Int): Int = value

fun main() {
    val text = stringLocal()
    println(text)
    var count = intLocal()
    count = intIdentity(count)
    intIdentity(count)
}
