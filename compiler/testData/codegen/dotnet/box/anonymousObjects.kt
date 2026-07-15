interface AnonymousIntValue {
    fun value(): Int
}

interface AnonymousValue<T> {
    fun value(): T
}

val topLevelAnonymous: AnonymousIntValue = object : AnonymousIntValue {
    override fun value(): Int = 13
}

fun bareAnonymous(seed: Int): Int {
    val value = object {
        fun value(): Int = seed + 1
    }
    return value.value()
}

fun interfaceAnonymous(seed: Int): Int {
    val offset = 2
    val value: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int = seed + offset
    }
    return value.value()
}

open class AnonymousBase(val left: Int, val right: Int) {
    open fun value(): Int = left * 10 + right
}

open class GenericAnonymousBase<T>(val base: T)

var anonymousOrder = ""

fun markAnonymous(tag: String, value: Int): Int {
    anonymousOrder += tag
    return value
}

fun superArguments(seed: Int): Int {
    anonymousOrder = ""
    val value = object : AnonymousBase(
        right = markAnonymous("R", 2),
        left = markAnonymous("L", 3),
    ) {
        private val own = markAnonymous("I", seed)

        override fun value(): Int = super.value() + own + seed
    }
    if (anonymousOrder != "RLI") return -1
    return value.value()
}

fun <T> passAnonymous(value: T): T = value

fun <T> genericSuperArgument(value: T): T {
    val source = object : GenericAnonymousBase<T>(passAnonymous(value)) {}
    return source.base
}

fun <T> genericAnonymous(value: T): T {
    val source: AnonymousValue<T> = object : AnonymousValue<T> {
        override fun value(): T = value
    }
    return source.value()
}

class AnonymousOwner<T>(private val seed: T) {
    fun source(): AnonymousValue<T> = object : AnonymousValue<T> {
        override fun value(): T = seed
    }
}

class AnonymousInInitializer(seed: Int) {
    val source: AnonymousIntValue

    init {
        val offset = 4
        source = object : AnonymousIntValue {
            override fun value(): Int = seed + offset
        }
    }
}

class AnonymousPropertyInitializer(seed: Int) {
    val source: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int = seed + 3
    }
}

fun nestedAnonymous(seed: Int): Int {
    val outer: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int {
            val inner: AnonymousIntValue = object : AnonymousIntValue {
                override fun value(): Int = seed + 2
            }
            return inner.value() + 1
        }
    }
    return outer.value()
}

fun freshAnonymousObjects(): Boolean {
    val first: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int = 1
    }
    val second: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int = 1
    }
    return first !== second
}

fun mixedNamedAndAnonymous(seed: Int): Int {
    class Local {
        fun value(): Int = seed
    }

    val value: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int = Local().value() + 1
    }
    return value.value()
}

fun mutableAnonymousCapture(): Int {
    var value = 1
    val source: AnonymousIntValue = object : AnonymousIntValue {
        override fun value(): Int = value
    }
    value = 42
    return source.value()
}

fun box(): String {
    if (topLevelAnonymous.value() != 13) return "fail 1: top-level property object"
    if (bareAnonymous(5) != 6) return "fail 2: bare anonymous object"
    if (interfaceAnonymous(7) != 9) return "fail 3: interface and captures"
    if (superArguments(5) != 42) return "fail 4: super argument, capture, and initializer order"
    if (genericSuperArgument("generic-super") != "generic-super") return "fail 5: generic super argument"
    if (genericAnonymous("generic") != "generic") return "fail 6: generic function capture"
    if (AnonymousOwner("owner").source().value() != "owner") return "fail 7: generic owner capture"
    if (AnonymousInInitializer(8).source.value() != 12) return "fail 8: initializer object"
    if (AnonymousPropertyInitializer(8).source.value() != 11) return "fail 9: property initializer object"
    if (nestedAnonymous(6) != 9) return "fail 10: nested object expressions"
    if (!freshAnonymousObjects()) return "fail 11: identity"
    if (mixedNamedAndAnonymous(9) != 10) return "fail 12: mixed locals"
    if (mutableAnonymousCapture() != 42) return "fail 13: mutable anonymous capture"
    return "OK"
}
