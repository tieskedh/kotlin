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

fun mutableLocalClassCapture(): Int {
    var value = 1

    class Local {
        fun value(): Int = value
    }

    val local = Local()
    value = 42
    return local.value()
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

fun box(): String {
    if (topLevelLocal(10) != 15) return "fail 1: top-level local capture"
    if (overloadedLocal(6) != 7) return "fail 2: first same-named local"
    if (overloadedLocal(6, 3) != 9) return "fail 3: second same-named local"
    if (localInheritance(20) != 21) return "fail 4: local inheritance and interface dispatch"
    if (genericFunctionLocal("generic", "bad") != "generic") return "fail 5: generic function capture"
    if (GenericLocalOwner("owner").value() != "owner") return "fail 6: generic owner capture"
    if (LocalInInitializer(8).value != 12) return "fail 7: local class in initializer"
    if (mutableLocalClassCapture() != 42) return "fail 8: mutable local-class capture"
    return "OK"
}
