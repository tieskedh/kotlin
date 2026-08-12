private fun interface IntTransform {
    fun transform(value: Int): Int
}

private fun interface OtherIntTransform {
    fun transform(value: Int): Int
}

private fun interface Producer<out T> {
    fun produce(): T
}

private fun interface Consumer<in T> {
    fun consume(value: T): String
}

private class Offset(private val amount: Int) {
    fun add(value: Int): Int = value + amount
}

private fun increment(value: Int): Int = value + 1

private fun String.decorate(): String = "<$this>"

private fun interface StringTransform {
    fun transform(value: String): String
}

fun box(): String {
    var factoryCalls = 0
    var invocationCalls = 0
    val evaluatedOnce = IntTransform(
        run {
            factoryCalls++
            { value ->
                invocationCalls++
                value + 1
            }
        }
    )
    if (factoryCalls != 1) return "fail 1: conversion evaluated $factoryCalls times"
    if (evaluatedOnce.transform(41) != 42 || invocationCalls != 1) return "fail 2: forwarding"

    val shared: (Int) -> Int = { it + 1 }
    val sharedFirst = IntTransform(shared)
    val sharedSecond = IntTransform(shared)
    if (sharedFirst === sharedSecond) return "fail 3: wrapper identity"
    if (sharedFirst != sharedSecond) return "fail 4: stored function equality"
    if (sharedFirst.hashCode() != sharedSecond.hashCode()) return "fail 5: stored function hash"
    if (sharedFirst.equals(OtherIntTransform(shared))) return "fail 6: cross-interface equality"

    val referenceFirst = IntTransform(::increment)
    val referenceSecond = IntTransform(::increment)
    if (referenceFirst === referenceSecond || referenceFirst != referenceSecond) {
        return "fail 7: function reference equality"
    }

    val receiver = Offset(2)
    val boundFirst = IntTransform(receiver::add)
    val boundSecond = IntTransform(receiver::add)
    if (boundFirst === boundSecond || boundFirst != boundSecond) return "fail 8: bound reference equality"
    if (boundFirst == IntTransform(Offset(2)::add)) return "fail 9: bound receiver identity"

    val directFirst = IntTransform { it + 1 }
    val directSecond = IntTransform { it + 1 }
    if (directFirst == directSecond) return "fail 10: distinct lambdas"

    val asAny: Any = sharedFirst
    if (asAny !is IntTransform || asAny.transform(41) != 42) return "fail 11: interface is-check"
    if (asAny as? OtherIntTransform != null) return "fail 12: unrelated safe cast"

    val stringProducer: Producer<String> = Producer { "OK" }
    val widenedProducer: Producer<Any> = stringProducer
    if (widenedProducer.produce() != "OK") return "fail 13: covariant generic producer"

    val anyConsumer: Consumer<Any> = Consumer { it.toString() }
    val stringConsumer: Consumer<String> = anyConsumer
    if (stringConsumer.consume("OK") != "OK") return "fail 14: contravariant generic consumer"

    val extension = StringTransform(String::decorate)
    if (extension.transform("OK") != "<OK>") return "fail 15: receiver adaptation"

    val nullableFunction: ((Int) -> Int)? = null
    fun invokeNullable(transform: IntTransform?): Int? = transform?.transform(41)
    if (invokeNullable(nullableFunction) != null) return "fail 16: nullable conversion"

    return "OK"
}
