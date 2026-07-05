package test

import kotlin.io.println

fun earlyReturn(flag: Boolean) {
    if (flag) return
    println("not returned")
}

fun bothBranchesReturn(flag: Boolean): String {
    if (flag) return "first" else return "second"
}

fun explicitReturn() {
    println("before return")
    return
}

fun main() {
    earlyReturn(true)
    earlyReturn(false)
    println(bothBranchesReturn(true))
    println(bothBranchesReturn(false))
    explicitReturn()
}
