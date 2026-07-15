data class GenericDefaultData<T>(val value: T, val label: String = "default")

fun defaultGenericData(): GenericDefaultData<Int> = GenericDefaultData(3)

fun copiedGenericData(): GenericDefaultData<Int> = GenericDefaultData(3).copy(value = 4)

fun main() {
    println(defaultGenericData().label)
}
