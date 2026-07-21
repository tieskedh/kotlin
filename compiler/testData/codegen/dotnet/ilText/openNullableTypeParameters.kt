class NullableHolder<T>(var value: T?)

interface NullableSlot<T> {
    fun nullable(): T?
}

class GenericNullableSlot<T>(private val stored: T?) : NullableSlot<T> {
    override fun nullable(): T? = stored
}

fun <T> echoNullable(value: T?): T? = value

fun <T> requireNullable(value: T?): T = value!!

fun primitiveNullable(value: Int?): Int? = echoNullable(value)

fun referenceNullable(value: String?): String? = echoNullable(value)

fun <T> readNullable(value: NullableSlot<T>): T? = value.nullable()

fun readNullableInt(value: NullableSlot<Int>): Int? = readNullable(value)

fun main() {
    println(primitiveNullable(7))
    println(referenceNullable("ok"))
    println(requireNullable(8))
}
