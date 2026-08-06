package test

import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
annotation class ExactValues(
    val booleanValue: Boolean,
    val byteValue: Byte,
    val shortValue: Short,
    val intValue: Int,
    val longValue: Long,
    val floatValue: Float,
    val doubleValue: Double,
    val charValue: Char,
    val stringValue: String,
    val stringArray: Array<String>,
)

@Retention(AnnotationRetention.RUNTIME)
annotation class ExactDefault(val value: Int = 42)

annotation class NestedValue(val value: String)

enum class KotlinEnum { FIRST, SECOND }

@Retention(AnnotationRetention.RUNTIME)
annotation class KotlinOnlyValues(
    val primitiveArray: IntArray,
    val klass: KClass<*>,
    val enumValue: KotlinEnum,
    val nested: NestedValue,
)

@Retention(AnnotationRetention.BINARY)
annotation class BinaryValue(val value: Int)

@ExactValues(
    true,
    -2,
    -3,
    0x12345678,
    0x0102030405060708,
    -0.0f,
    Double.NaN,
    '\u03bb',
    "hé",
    ["a", "β"],
)
@ExactDefault
@KotlinOnlyValues([1, 2], String::class, KotlinEnum.SECOND, NestedValue("nested"))
@BinaryValue(7)
class Applied

fun constructValues(): String {
    val left = ExactDefault()
    val right = ExactDefault(42)
    return if (left == right) left.toString() else "FAIL"
}
