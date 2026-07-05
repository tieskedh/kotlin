package test

import kotlin.io.println

fun printOk() {
    println("OK")
}

fun printNullable(value: String?) {
    println(value + "")
}

fun callPrintOk() {
    printOk()
}

fun ignoredIntCall() {
    intValue()
}

fun intValue(): Int = 42

fun main() {
    callPrintOk()
    printNullable(null)
}
