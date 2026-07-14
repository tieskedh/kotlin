// Unsupported nested shapes reject their entire top-level class family. BrokenFamily reaches
// the member pre-pass before failing: Bad's Byte signature evicts Good and the root together,
// then the fixpoint also removes UsesBroken's reference to the now-unavailable nested type.
// DeepBrokenFamily instead fails while recursively rendering a three-level nested member body,
// exercising preservation of the deepest failure attribution while the whole family is evicted.
// BrokenNestedBaseFamily pins the inheritance cascade: its nested base fails the member pre-pass,
// then both a top-level derived class and a separate nested family disappear in the render fixpoint.
// Generic companion containers stay rejected because the companion field would live on the
// constructed generic owner. Named objects own their own non-generic INSTANCE field and are
// covered positively by nestedSingletons.
// BrokenNestedSingletonFamily pins recursive-static-initializer eviction after its callee
// disappears.

class InnerHost {
    inner class Nested
}

object ObjectHost {
    class Nested
}

class NestedInterfaceHost {
    interface Nested
}

class NestedDataHost {
    data class Nested(val value: Int)
}

class GenericCompanionHost<T> {
    companion object
}

class GenericNestedCompanionHost {
    class Nested<T> {
        companion object
    }
}

class BrokenFamily {
    class Good {
        fun value(): Int = 1
    }

    class Bad {
        fun unsupported(value: Byte): Byte = value
    }
}

class UsesBroken {
    fun use(value: BrokenFamily.Good): Int = value.value()
}

class BrokenNestedBaseFamily {
    open class Base {
        fun unsupported(value: Byte): Byte = value
    }
}

class DerivedFromBrokenNestedBase : BrokenNestedBaseFamily.Base()

class NestedDerivedFromBrokenNestedBase {
    class Derived : BrokenNestedBaseFamily.Base()
}

fun brokenNestedSingletonInitializer(): Int {
    var count = 0
    for (value in 1L..2L) {
        count += 1
    }
    return count
}

class BrokenNestedSingletonFamily {
    class Nested {
        companion object {
            val captured: Int = brokenNestedSingletonInitializer()
        }
    }
}

class DeepBrokenFamily {
    class Middle {
        class Bad {
            fun unsupported(): Long {
                var lastSeen = 0L
                for (value in 1L..3L) {
                    lastSeen = value
                }
                return lastSeen
            }
        }
    }
}

class Survives {
    fun value(): Int = 17
}

fun main() {
    println(Survives().value())
}
