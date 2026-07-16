package sample

fun source(prefix: Int, callback: (Int) -> Int = { it + prefix }): Int = callback(prefix)

fun clash(prefix: Int): Int = prefix
