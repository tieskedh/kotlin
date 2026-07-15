// TARGET_BACKEND: DOTNET

private interface Marker {
    val id: Int
}

private class MarkerImpl(override val id: Int) : Marker

private data class Constrained<T : Marker>(val value: T)

private class GenericOuter<T> {
    data class Nested<U>(val value: U)
}

fun box(): String {
    val marker = MarkerImpl(7)
    if (!Constrained<Marker>(marker).equals(Constrained(marker))) return "fail 1: constrained type"

    val nestedAny = GenericOuter.Nested<Any?>(null)
    val nestedString = GenericOuter.Nested<String?>(null)
    if (!nestedAny.equals(nestedString)) return "fail 2: generic nested data class"

    return "OK"
}
