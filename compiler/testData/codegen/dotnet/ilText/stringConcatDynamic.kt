package test

import kotlin.io.println

fun describe(count: Int, enabled: Boolean): String = "count: " + count + ", enabled: " + enabled

fun main() {
    val x = 12
    val ready = x > 10
    val message = "x=$x and ready=$ready"
    println(message)
    println("ready=" + ready)
    println(describe(x - 10, x == 12))
    println(x.toString())
    println(ready.toString())
    println("" + x + " " + !ready)
}
