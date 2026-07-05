package test

import kotlin.io.println

fun value(s: String?): String = s + ""

fun stringConcat(s: String): String = "O" + s

fun main() {
    println(value(null))
    println(stringConcat("K"))
}
