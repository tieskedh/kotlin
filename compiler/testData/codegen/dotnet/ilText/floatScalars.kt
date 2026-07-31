package test

const val FLOAT_CONST: Float = -1.25f

fun add(left: Float, right: Float): Float = left + right
fun mixed(left: Long, right: Float): Float = left + right
fun widen(value: Float): Double = value.toDouble()
fun narrow(value: Double): Float = value.toFloat()
fun toInt(value: Float): Int = value.toInt()
fun nullable(value: Float?): Float = value ?: 2.5f
fun generic(values: Array<Float>): Float = values[0]
fun render(value: Float): String = value.toString()

fun main() {
    println(FLOAT_CONST)
    println(add(1.25f, 2.5f))
    println(mixed(16_777_216L, 1f))
    println(widen(0.1f))
    println(narrow(0.1))
    println(toInt(12.75f))
    println(nullable(null))
    println(generic(arrayOf(3.5f)))
    println(render(-0.0f))
}
