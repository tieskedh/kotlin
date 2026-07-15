fun sum(vararg values: Int): Int = values[0] + values[1]

fun join(vararg values: String?): String =
    (values[0] ?: "null") + (values[1] ?: "null")

class VarargMember {
    fun combine(prefix: Int, vararg values: Long): Long = prefix.toLong() + values[0]
}

class VarargConstructor(vararg values: Char) {
    val first: Char = values[0]
}

interface VarargInterface {
    fun collect(vararg values: Double): Double
}

class VarargInterfaceImpl : VarargInterface {
    override fun collect(vararg values: Double): Double = values[0]
}

fun empty(): Int = sum()

fun literals(): Int = sum(1, 2)

fun primitiveSpread(values: IntArray): Int = sum(1, *values, 2)

fun referenceSpread(values: Array<String?>): String = join("a", *values, null)

fun arrayOfSpread(values: IntArray): IntArray = intArrayOf(1, *values, 2)

fun genericArrayOfSpread(values: Array<String>): Array<String> = arrayOf("a", *values, "b")

fun member(value: VarargMember): Long = value.combine(2, 3L)

fun constructed(): Char = VarargConstructor('A').first

fun interfaceCall(value: VarargInterface): Double = value.collect(1.5)

fun withDefault(prefix: Int = 10, vararg values: Int): Int = prefix + sum(*values)

fun defaultCall(): Int = withDefault(values = intArrayOf(1, 2))

fun configured(vararg values: Int = intArrayOf(7, 8)): Int = sum(*values)

fun configuredCall(): Int = configured()

fun main() {
    println(literals())
}
