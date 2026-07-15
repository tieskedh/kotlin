// Anonymous objects use the local-class closure conversion after their complex base-constructor
// arguments move to the object-expression call site. Their lifted CLR types remain inaccessible;
// immutable value/receiver and type-parameter captures become explicit metadata inputs.

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

fun main() {
    println(topLevelAnonymous.value())
    println(bareAnonymous(5))
    println(interfaceAnonymous(7))
    println(superArguments(5))
    println(genericSuperArgument("generic-super"))
    println(genericAnonymous("generic"))
    println(AnonymousOwner("owner").source().value())
    println(AnonymousInInitializer(8).source.value())
    println(AnonymousPropertyInitializer(8).source.value())
    println(nestedAnonymous(6))
    println(freshAnonymousObjects())
    println(mixedNamedAndAnonymous(9))
}
