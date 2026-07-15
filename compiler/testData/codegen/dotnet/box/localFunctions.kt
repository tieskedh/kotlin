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

fun mutableLocalFunctionCapture(): Int {
    var value = 1

    fun local(): Int = value

    value = 42
    return local()
}

fun box(): String {
    if (localCapture(10) != 15) return "fail 1: immutable captures"
    if (`localCapture$add`(1, 2, 3) != -1) return "fail 2: facade user-name priority"
    if (recursiveLocal(4) != 10) return "fail 3: recursion"
    if (nestedLocal(5) != 10) return "fail 4: nested local functions"
    if (overloadedLocalFunction(6) != 7) return "fail 5: first same-named local"
    if (overloadedLocalFunction(6, 3) != 9) return "fail 6: second same-named local"
    if (genericLocalFunction("generic") != "generic") return "fail 7: generic local function"
    if (GenericLocalFunctionOwner("owner").value() != "owner") return "fail 8: generic owner receiver"
    if (GenericLocalFunctionNoReceiver<String>().value("no-owner") != "no-owner") return "fail 9: generic owner fallback"
    if (LocalFunctionInInitializer(8).value != 12) return "fail 10: initializer local function"
    val collision = MemberLocalFunctionCollision()
    if (collision.run(9) != 12) return "fail 11: member local-name collision"
    if (collision.`run$local`(1, 2) != -2) return "fail 12: member user-name priority"
    if (localClassCallsFunction(9) != 12) return "fail 13: local class calls local function"
    if (anonymousObjectCallsFunction(9) != 13) return "fail 14: anonymous object calls local function"
    if (mutableLocalFunctionCapture() != 42) return "fail 15: mutable local-function capture"
    return "OK"
}
interface LocalFunctionIntValue {
    fun value(): Int
}
