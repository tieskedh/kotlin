package test.constraints.rejected

open class LocalBase

open class GenericBase<T>(val value: T)

interface LocalMark

class BrokenBound {
    fun <T> unsupported(values: Array<T?>): Array<T?> = values
}

fun <T : BrokenBound> evictedFunctionBound(value: T): T = value

class EvictedClassBound<T : BrokenBound>(val value: T)

fun <T : Any> anyBound(value: T): T = value

fun <T : LocalBase?> nullableBound(value: T): T = value

fun <T : GenericBase<String>> genericBound(value: T): T = value

fun <T : CharSequence> builtinBound(value: T): T = value

fun <T : Exception> mappedBound(value: T): T = value

fun main() {
}
