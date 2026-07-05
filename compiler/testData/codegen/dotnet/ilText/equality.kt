package test

import kotlin.io.println

fun intEquals(value: Int): Boolean = value == 42

fun intNotEquals(value: Int): Boolean = value != 42

fun booleanEquals(value: Boolean): Boolean = value == true

fun stringEquals(value: String): Boolean = value == "OK"

fun nullableStringEquals(value: String?): Boolean = value == null

fun branchOnEquals(value: Int): String =
    if (value == 42) "OK" else "FAIL"

fun main() {
    if (intEquals(42)) println("OK") else println("FAIL")
    println(if (stringEquals("OK")) "OK" else "FAIL")
}
