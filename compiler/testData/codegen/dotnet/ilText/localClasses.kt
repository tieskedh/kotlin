// Named local classes are closure-converted and popped up to CLR metadata. Locals in top-level
// functions become module-private top-level types; locals in members become private nested types.
// Immutable values/receivers become constructor parameters and fields. Captured type parameters
// are duplicated on the local type, keeping its `!n` space independent from a generic metadata
// parent.

interface LocalValue {
    fun value(): Int
}

fun topLevelLocal(seed: Int): Int {
    val offset = 2

    class Local(private val add: Int) : LocalValue {
        private val initialized: Int = seed + offset + add

        override fun value(): Int = initialized
    }

    return Local(3).value()
}

fun overloadedLocal(value: Int): Int {
    class SameName {
        fun value(): Int = value + 1
    }

    return SameName().value()
}

fun overloadedLocal(value: Int, extra: Int): Int {
    class SameName {
        fun value(): Int = value + extra
    }

    return SameName().value()
}

fun localInheritance(seed: Int): Int {
    open class Base(private val value: Int) {
        open fun value(): Int = value
    }

    class Derived : Base(seed), LocalValue {
        override fun value(): Int = super.value() + 1
    }

    val view: LocalValue = Derived()
    return view.value()
}

fun <T> genericFunctionLocal(value: T, failure: T): T {
    class Local<U>(private val captured: T, private val own: U) {
        fun captured(): T = captured

        fun own(): U = own
    }

    val local = Local(value, 5)
    if (local.own() != 5) return failure
    return local.captured()
}

class GenericLocalOwner<T>(private val seed: T) {
    fun value(): T {
        class Local {
            fun value(): T = seed
        }

        return Local().value()
    }
}

class LocalInInitializer(seed: Int) {
    val value: Int

    init {
        val offset = 4

        class Local {
            fun value(): Int = seed + offset
        }

        value = Local().value()
    }
}

fun main() {
    println(topLevelLocal(10))
    println(overloadedLocal(6))
    println(overloadedLocal(6, 3))
    println(localInheritance(20))
    println(genericFunctionLocal("generic", "bad"))
    println(GenericLocalOwner("owner").value())
    println(LocalInInitializer(8).value)
}
