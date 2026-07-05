package test

fun intValue(): Int = 42

fun intIdentity(value: Int): Int = value

fun intCall(): Int = intIdentity(7)

fun booleanValue(): Boolean = true

fun booleanIdentity(value: Boolean): Boolean = value

fun booleanCall(): Boolean = booleanIdentity(false)

fun stringIdentity(value: String): String = value

fun main() {
}
