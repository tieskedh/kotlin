// Unsupported nested shapes reject their entire top-level class family. BrokenFamily reaches
// the member pre-pass before failing: Bad's Byte signature evicts Good and the root together,
// then the fixpoint also removes UsesBroken's reference to the now-unavailable nested type.
// DeepBrokenFamily instead fails while recursively rendering a three-level nested member body,
// exercising preservation of the deepest failure attribution while the whole family is evicted.

class OpenNestedHost {
    open class Nested
}

class AbstractNestedHost {
    abstract class Nested
}

class InnerHost {
    inner class Nested
}

class NestedObjectHost {
    object Nested
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

class NestedCompanionHost {
    class Nested {
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
