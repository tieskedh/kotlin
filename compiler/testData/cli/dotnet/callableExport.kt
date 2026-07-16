package sample

fun makeAdder(offset: Int): (Int) -> Int = { value -> value + offset }
