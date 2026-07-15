// Explicit local functions become assembly-static methods on a file facade or private-static
// methods on the nearest class.
// Immutable value/receiver captures become parameters; captured type parameters are duplicated
// into the lifted method's independent `!!n` space.

interface LocalFunctionIntValue {
    fun value(): Int
}

fun localCapture(seed: Int): Int {
    val offset = 2

    fun add(value: Int): Int = seed + offset + value

    return add(3)
}

fun `localCapture$add`(seed: Int, offset: Int, value: Int): Int = -1

fun recursiveLocal(value: Int): Int {
    fun sum(current: Int): Int = if (current == 0) 0 else current + sum(current - 1)

    return sum(value)
}

fun nestedLocal(seed: Int): Int {
    fun outer(extra: Int): Int {
        fun inner(value: Int): Int = seed + extra + value

        return inner(3)
    }

    return outer(2)
}

fun overloadedLocalFunction(value: Int): Int {
    fun same(): Int = value + 1

    return same()
}

fun overloadedLocalFunction(value: Int, extra: Int): Int {
    fun same(): Int = value + extra

    return same()
}

fun <T> genericLocalFunction(value: T): T {
    fun <U> choose(ignored: U): T = value

    return choose(5)
}

class GenericLocalFunctionOwner<T>(private val seed: T) {
    fun value(): T {
        fun <U> U.local(): T = seed

        return "receiver".local()
    }
}

class GenericLocalFunctionNoReceiver<T> {
    fun value(input: T): T {
        fun local(item: T): T = item

        return local(input)
    }
}

class LocalFunctionInInitializer(seed: Int) {
    val value: Int

    init {
        fun compute(): Int = seed + 4

        value = compute()
    }
}

class MemberLocalFunctionCollision {
    fun `run$local`(captured: Int, value: Int): Int = -2

    fun run(seed: Int): Int {
        fun local(value: Int): Int = seed + value

        return local(3)
    }
}

fun localClassCallsFunction(seed: Int): Int {
    fun compute(value: Int): Int = seed + value

    class Local {
        fun value(): Int = compute(3)
    }

    return Local().value()
}

fun anonymousObjectCallsFunction(seed: Int): Int {
    fun compute(value: Int): Int = seed + value

    val source: LocalFunctionIntValue = object : LocalFunctionIntValue {
        override fun value(): Int = compute(4)
    }
    return source.value()
}

fun main() {
    println(localCapture(10))
    println(`localCapture$add`(1, 2, 3))
    println(recursiveLocal(4))
    println(nestedLocal(5))
    println(overloadedLocalFunction(6))
    println(overloadedLocalFunction(6, 3))
    println(genericLocalFunction("generic"))
    println(GenericLocalFunctionOwner("owner").value())
    println(GenericLocalFunctionNoReceiver<String>().value("no-owner"))
    println(LocalFunctionInInitializer(8).value)
    val collision = MemberLocalFunctionCollision()
    println(collision.run(9))
    println(collision.`run$local`(1, 2))
    println(localClassCallsFunction(9))
    println(anonymousObjectCallsFunction(9))
}
