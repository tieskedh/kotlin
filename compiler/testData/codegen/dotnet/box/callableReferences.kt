import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1

private class ReflectedBound(private val value: Int) {
    fun read(): Int = value
}

private fun reflectedIncrement(value: Int): Int = value + 1

private fun reflectedLabel(ignored: Any): String = "label"

private fun reflectedValue(): Int = 42

private fun overloaded(value: Int): Int = value + 1

private fun overloaded(value: String): Int = 2

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

    val first: (Int) -> Int = ::reflectedIncrement
    val second: (Int) -> Int = ::reflectedIncrement
    if (first === second || first != second) return "fail 9: cross-site equality"
    if (first.hashCode() != second.hashCode()) return "fail 10: cross-site hash"
    if (first.toString() != "function reflectedIncrement") return "fail 11: function rendering"

    val intOverload: (Int) -> Int = ::overloaded
    val stringOverload: (String) -> Int = ::overloaded
    if (intOverload.equals(stringOverload)) return "fail 12: overload identity"

    val receiver = ReflectedBound(42)
    val boundFirst = receiver::read
    val boundSecond = receiver::read
    if (boundFirst === boundSecond || boundFirst != boundSecond) return "fail 13: bound equality"
    if (boundFirst.hashCode() != boundSecond.hashCode()) return "fail 14: bound hash"
    if (boundFirst == ReflectedBound(42)::read) return "fail 15: distinct bound receiver"

    val lambdaFirst: () -> Int = { 42 }
    val lambdaSecond: () -> Int = { 42 }
    if (lambdaFirst == lambdaSecond) return "fail 16: lambda equality"

    val primitiveBoundFirst: () -> String = 41::toString
    val primitiveBoundSecond: () -> String = 41::toString
    if (primitiveBoundFirst != primitiveBoundSecond) return "fail 17: primitive bound equality"
    if (primitiveBoundFirst.hashCode() != primitiveBoundSecond.hashCode()) return "fail 18: primitive bound hash"

    val valueReference: () -> Int = ::reflectedValue
    val unitReference: () -> Unit = ::reflectedValue
    if (valueReference.equals(unitReference)) return "fail 19: adapted reference identity"
    if (valueReference !is KFunction<*>) return "fail 19a: exact reference lost KFunction identity"
    if (unitReference is KFunction<*>) return "fail 19b: adapted reference gained KFunction identity"

    val constructor: (Int) -> ReflectedBound = ::ReflectedBound
    if (constructor.toString() != "constructor") return "fail 20: constructor rendering"

    return "OK"
}
