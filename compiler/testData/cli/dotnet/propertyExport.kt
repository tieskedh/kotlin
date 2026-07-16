package sample

var value: String? = null

val Int.shifted: Int
    get() = this + 1

fun readValue(): String? = value
