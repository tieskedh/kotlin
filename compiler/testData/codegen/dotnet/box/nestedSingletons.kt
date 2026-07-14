var initializationCount: Int = 0

fun nextInitialization(): Int {
    initializationCount += 1
    return initializationCount
}

class DirectOwner {
    class Nested {
        companion object {
            val captured: Int = nextInitialization()
        }
    }
}

class ObjectOwner {
    private object Hidden {
        val captured: Int = nextInitialization()
    }

    fun hiddenValue(): Int = Hidden.captured
}

class DeepOwner {
    class Middle {
        class Nested {
            companion object {
                val captured: Int = nextInitialization()
            }
        }
    }
}

class GenericAncestor<T> {
    class CompanionHolder {
        companion object {
            val captured: Int = nextInitialization()
        }
    }

    class ObjectHolder {
        object Singleton {
            val captured: Int = nextInitialization()
        }
    }
}

class ModalOwner {
    open class OpenNested {
        companion object {
            val captured: Int = nextInitialization()
        }
    }

    abstract class AbstractNested {
        companion object {
            val captured: Int = nextInitialization()
        }
    }
}

class AccessOwner {
    class Nested(private val seed: Int) {
        fun throughCompanion(): Int = double(seed)

        companion object {
            private fun double(value: Int): Int = value + value

            fun read(value: Nested): Int = value.seed
        }
    }
}

class ForwardOwner {
    class Factory {
        companion object {
            fun create(value: Int): Later = Later(value)
        }
    }

    class Later(val value: Int)
}

class HierarchyOwner {
    open class Base {
        companion object {
            val captured: Int = nextInitialization()
        }
    }

    class Derived : Base()
}

fun box(): String {
    if (initializationCount != 0) return "fail 1: eager initialization"
    if (DirectOwner.Nested.captured != 1) return "fail 2: direct nested companion"
    if (DirectOwner.Nested.captured != 1) return "fail 3: repeated companion access"
    if (initializationCount != 1) return "fail 4: companion initialized more than once"
    if (ObjectOwner().hiddenValue() != 2) return "fail 5: private named nested object"
    if (DeepOwner.Middle.Nested.captured != 3) return "fail 6: deep nested companion"
    if (GenericAncestor.CompanionHolder.captured != 4) return "fail 7: companion below generic ancestor"
    if (GenericAncestor.ObjectHolder.Singleton.captured != 5) return "fail 8: object below generic ancestor"
    if (ModalOwner.OpenNested.captured != 6) return "fail 9: open nested owner"
    if (ModalOwner.AbstractNested.captured != 7) return "fail 10: abstract nested owner"
    if (HierarchyOwner.Base.captured != 8) return "fail 11: nested inheritance coexistence"
    if (initializationCount != 8) return "fail 12: initialization count"

    val access = AccessOwner.Nested(7)
    if (access.throughCompanion() != 14) return "fail 13: enclosing to private companion access"
    if (AccessOwner.Nested.read(access) != 7) return "fail 14: companion to private owner access"
    if (ForwardOwner.Factory.create(12).value != 12) return "fail 15: forward nested reference"
    if (initializationCount != 8) return "fail 16: stateless singleton construction"
    return "OK"
}
