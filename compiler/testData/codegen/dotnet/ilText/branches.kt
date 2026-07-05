package test

import kotlin.io.println

fun chooseString(flag: Boolean): String =
    if (flag) "OK" else "FAIL"

fun chooseInt(flag: Boolean): Int {
    val value = if (flag) 1 else 2
    return value
}

fun chooseBoolean(flag: Boolean): Boolean =
    if (flag) false else true

fun printBranch(flag: Boolean) {
    if (flag) println("OK") else println("FAIL")
}

fun main() {
    println(chooseString(true))
    printBranch(false)
}
