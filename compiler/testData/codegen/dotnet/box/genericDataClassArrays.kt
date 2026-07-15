// TARGET_BACKEND: DOTNET

private data class ArrayBox<T>(val value: T)

private data class ArrayData<T>(val marker: T, val values: IntArray)

fun box(): String {
    val sameArray = intArrayOf(1, 2)
    if (!ArrayBox(sameArray).equals(ArrayBox<Any>(sameArray))) return "fail 1: shared array"
    if (ArrayBox(intArrayOf(1, 2)).equals(ArrayBox<Any>(intArrayOf(1, 2)))) return "fail 2: array identity"

    val arrayAny = ArrayData<Any?>(null, sameArray)
    val arrayString = ArrayData<String?>(null, sameArray)
    if (!arrayAny.equals(arrayString)) return "fail 3: concrete array property"
    if (arrayAny.hashCode() != arrayString.hashCode() || arrayAny.toString() != "ArrayData(marker=null, values=[1, 2])") {
        return "fail 4: array generated members $arrayAny"
    }

    return "OK"
}
