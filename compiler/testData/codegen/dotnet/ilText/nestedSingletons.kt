// Static singleton initialization follows the JVM's recursive class-lowering model. A companion
// field lives on its immediate non-generic owner; a named object's INSTANCE field lives on the
// object itself. Each actual field owner receives its own CLR .cctor, at arbitrary metadata depth.

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

class DirectGenericObjectOwner<T> {
    object Singleton {
        val captured: Int = nextInitialization()
    }
}

class GenericNestedObjectOwner {
    class Generic<T> {
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

fun main() {
    println(initializationCount)
    println(DirectOwner.Nested.captured)
    println(DirectOwner.Nested.captured)
    println(initializationCount)
    println(ObjectOwner().hiddenValue())
    println(DeepOwner.Middle.Nested.captured)
    println(GenericAncestor.CompanionHolder.captured)
    println(GenericAncestor.ObjectHolder.Singleton.captured)
    println(DirectGenericObjectOwner.Singleton.captured)
    println(GenericNestedObjectOwner.Generic.Singleton.captured)
    println(ModalOwner.OpenNested.captured)
    println(ModalOwner.AbstractNested.captured)
    println(HierarchyOwner.Base.captured)
    println(initializationCount)

    val access = AccessOwner.Nested(7)
    println(access.throughCompanion())
    println(AccessOwner.Nested.read(access))
    println(ForwardOwner.Factory.create(12).value)
    println(initializationCount)
}
