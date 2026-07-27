package sample

internal fun internalAnswer(): Int = 42

fun answer(): Int = internalAnswer()
