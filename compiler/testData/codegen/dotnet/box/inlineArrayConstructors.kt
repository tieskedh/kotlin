// LANGUAGE: +ConsiderLambdaArrayConstructorsInlinableInBodiesOfInlineFunctions

private inline fun primitiveArray(size: Int, initializer: (Int) -> Int): IntArray =
    IntArray(size, initializer)

private inline fun genericArray(size: Int, initializer: (Int) -> String): Array<String> =
    Array(size, initializer)

fun box(): String {
    var offset = 10
    val primitive = primitiveArray(3) { index -> offset + index }
    offset = 20
    if (primitive[0] != 10 || primitive[1] != 11 || primitive[2] != 12) {
        return "FAIL: primitive array"
    }

    var calls = 0
    val generic = genericArray(2) { index ->
        calls++
        "v$index"
    }
    if (calls != 2 || generic[0] != "v0" || generic[1] != "v1") {
        return "FAIL: generic array"
    }
    return "OK"
}
