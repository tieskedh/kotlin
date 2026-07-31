package test

import kotlin.io.println

const val BYTE_CONST: Byte = -8
const val SHORT_CONST: Short = -300

class NarrowBox<T>(val value: T)

fun overload(value: Byte): String = "byte:" + value
fun overload(value: Short): String = "short:" + value
fun overload(value: Int): String = "int:" + value

fun byteInc(start: Byte): Byte {
    var value = start
    value++
    return value
}

fun shortDec(start: Short): Short {
    var value = start
    value--
    return value
}

fun intToByte(value: Int): Byte = value.toByte()
fun longToShort(value: Long): Short = value.toShort()
fun doubleToByte(value: Double): Byte = value.toInt().toByte()
fun nullableByte(value: Byte?): Byte = value ?: 11

fun main() {
    println(overload((-7).toByte()))
    println(overload((-700).toShort()))
    println(overload(-700))
    println(byteInc(127))
    println(shortDec(-32768))
    println(intToByte(130))
    println(longToShort(65535L))
    println(doubleToByte(1.0 / 0.0))
    println(nullableByte(null))
    println(NarrowBox((-9).toByte()).value)
    println(arrayOf<Byte>(-128, 0, 127)[0])
}
