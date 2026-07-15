// TARGET_BACKEND: DOTNET

private data class PairData<A, B>(val first: A, val second: B)

private class Token(val text: String)

fun box(): String {
    val token = Token("same")
    val pairAny = PairData<Any?, Any?>(null, token)
    val pairExact = PairData<String?, Token>(null, token)
    return if (pairAny.equals(pairExact) && pairAny.hashCode() == pairExact.hashCode()) {
        "OK"
    } else {
        "fail"
    }
}
