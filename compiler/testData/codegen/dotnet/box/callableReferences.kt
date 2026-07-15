import kotlin.reflect.KFunction1

private class ReflectedBound(private val value: Int) {
    fun read(): Int = value
}

private fun reflectedIncrement(value: Int): Int = value + 1

private fun reflectedLabel(ignored: Any): String = "label"

private fun reflected() = ::reflectedIncrement

private fun nameOf(reference: KFunction1<Int, Int>): String = reference.name

private fun invoke(reference: KFunction1<Int, Int>, value: Int): Int = reference(value)

fun box(): String {
    val reference = reflected()
    if (nameOf(reference) != "reflectedIncrement") return "fail 1: name"
    if (invoke(reference, 41) != 42) return "fail 2: KFunction invocation"

    val callable: (Int) -> Int = reference
    if (reference !== callable) return "fail 3: Function view identity"
    if (reflected() !== reflected()) return "fail 4: non-capturing reference cache"

    val narrow: KFunction1<Any, String> = ::reflectedLabel
    val wide: KFunction1<Int, Any> = narrow
    if (narrow !== wide) return "fail 5: KFunction variance identity"

    val bound = ReflectedBound(42)::read
    if (bound.name != "read") return "fail 6: bound name"
    if (bound() != 42) return "fail 7: bound invocation"
    if (bound === ReflectedBound(42)::read) return "fail 8: bound reference cache"
    return "OK"
}
