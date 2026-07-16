package sample

fun makeAdder(offset: Int): (Int) -> Int = { value -> value + offset }

fun applyTwice(transform: (Int) -> Int, value: Int): Int = transform(transform(value))
