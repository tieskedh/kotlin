// Unsupported nested shapes remove only their own metadata subtree. BrokenFamily reaches the
// member pre-pass before Bad's open `Array<T?>` signature fails; Good, the root, and UsesBroken survive.
// DeepBrokenFamily instead fails while recursively rendering a three-level nested member body,
// exercising deepest-failure attribution while its valid metadata ancestors survive.
// BrokenNestedBaseFamily pins the inheritance cascade: its nested base fails the member pre-pass,
// then actual derived classes disappear in the render fixpoint, while their independent enclosing
// metadata parents survive.
// Generic companion containers now survive through a non-generic static holder. Named objects
// own their own non-generic INSTANCE field and are covered positively by nestedSingletons.
// BrokenNestedSingletonFamily pins owner-sensitive recursive-static-initializer eviction after
// its callee disappears: Nested owns the companion field/.cctor and is removed, but its parent
// survives.

class GenericInnerHost<T> {
    class Good {
        fun value(): Int = 2
    }
}

class BrokenInnerHost {
    inner class Bad {
        fun <T> unsupported(value: Array<T?>): Array<T?> = value
    }

    class Good {
        fun value(): Int = 3
    }
}

class NestedAnnotationHost {
    annotation class Nested(val value: Int)
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
        fun <T> unsupported(value: Array<T?>): Array<T?> = value
    }
}

class UsesBroken {
    fun use(value: BrokenFamily.Good): Int = value.value()
}

class BrokenNestedBaseFamily {
    open class Base {
        fun <T> unsupported(value: Array<T?>): Array<T?> = value
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
    println(GenericInnerHost.Good().value())
    println(BrokenInnerHost.Good().value())
    println(UsesBroken().use(BrokenFamily.Good()))
    println(Survives().value())
}
