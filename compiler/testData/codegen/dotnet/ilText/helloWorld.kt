package test

import kotlin.io.print
import kotlin.io.println
import kotlin.io.readln
import kotlin.io.readlnOrNull

fun readRequired(): String = readln()

fun readOptional(): String? = readlnOrNull()

fun printAny(value: Any?) {
    print(value)
}

fun main() {
    println("Hello!")
}
