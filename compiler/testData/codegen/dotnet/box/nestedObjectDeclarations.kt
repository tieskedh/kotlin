var nestedOwnerInitializationCount: Int = 0

fun nextNestedOwnerInitialization(): Int {
    nestedOwnerInitializationCount += 1
    return nestedOwnerInitializationCount
}

interface NestedOwnerContract {
    fun value(): Int
}

object ObjectDeclarationHost {
    private val secret: Int = 6

    class Reader {
        fun read(): Int = ObjectDeclarationHost.secret
    }

    open class Base(private val stored: Int) : NestedOwnerContract {
        override fun value(): Int = stored
    }

    class Derived(stored: Int) : Base(stored)

    class Independent<T>(val item: T)

    private class Hidden(val item: Int)

    internal class Internal(val item: Int)

    fun hiddenValue(): Int = Hidden(7).item

    object NestedObject {
        val initialized: Int = nextNestedOwnerInitialization()

        class Leaf(val item: Int)
    }

    class CompanionOwner {
        companion object {
            val initialized: Int = nextNestedOwnerInitialization()
        }
    }

    class ForwardFactory {
        fun create(value: Int): Later = Later(value)
    }

    class Later(val item: Int)
}

class ObjectDeclarationDerived(value: Int) : ObjectDeclarationHost.Base(value)

class CompanionDeclarationHost {
    companion object {
        private val secret: Int = 11

        class Reader {
            fun read(): Int = CompanionDeclarationHost.secret
        }

        open class Base(private val stored: Int) : NestedOwnerContract {
            override fun value(): Int = stored
        }

        class Derived(stored: Int) : Base(stored)

        class Independent<T>(val item: T)

        private class Hidden(val item: Int)

        internal class Internal(val item: Int)

        fun hiddenValue(): Int = Hidden(12).item

        object NestedObject {
            val initialized: Int = nextNestedOwnerInitialization()

            class Leaf(val item: Int)
        }

        class CompanionOwner {
            companion object {
                val initialized: Int = nextNestedOwnerInitialization()
            }
        }

        class ForwardFactory {
            fun create(value: Int): Later = Later(value)
        }

        class Later(val item: Int)
    }
}

class CompanionDeclarationDerived(value: Int) : CompanionDeclarationHost.Companion.Base(value)

interface GenericMetadataOwner<T> {
    object Singleton {
        class Independent<U>(val item: U)

        object NestedObject {
            val initialized: Int = nextNestedOwnerInitialization()

            class Leaf(val item: Int)
        }

        class CompanionOwner {
            companion object {
                val initialized: Int = nextNestedOwnerInitialization()
            }
        }
    }
}

fun box(): String {
    if (nestedOwnerInitializationCount != 0) return "fail 1: eager nested singleton initialization"
    if (ObjectDeclarationHost.Reader().read() != 6) return "fail 2: object nested-to-enclosing access"
    if (ObjectDeclarationHost.hiddenValue() != 7) return "fail 3: private class in object"
    val objectContract: NestedOwnerContract = ObjectDeclarationHost.Derived(8)
    if (objectContract.value() != 8) return "fail 4: object nested interface dispatch"
    if (ObjectDeclarationDerived(9).value() != 9) return "fail 5: object nested external inheritance"
    if (ObjectDeclarationHost.Independent("ten").item != "ten") return "fail 6: object nested generic"
    if (ObjectDeclarationHost.Internal(18).item != 18) return "fail 7: object nested visibility"
    if (ObjectDeclarationHost.NestedObject.initialized != 1) return "fail 8: object in object"
    if (ObjectDeclarationHost.NestedObject.Leaf(20).item != 20) return "fail 9: class in nested object"
    if (ObjectDeclarationHost.CompanionOwner.initialized != 2) return "fail 10: companion below object"
    if (ObjectDeclarationHost.ForwardFactory().create(10).item != 10) return "fail 11: object forward reference"

    if (CompanionDeclarationHost.Companion.Reader().read() != 11) {
        return "fail 12: companion nested-to-enclosing access"
    }
    if (CompanionDeclarationHost.hiddenValue() != 12) return "fail 13: private class in companion"
    val companionContract: NestedOwnerContract = CompanionDeclarationHost.Companion.Derived(13)
    if (companionContract.value() != 13) return "fail 14: companion nested interface dispatch"
    if (CompanionDeclarationDerived(14).value() != 14) return "fail 15: companion nested inheritance"
    if (CompanionDeclarationHost.Companion.Independent("fifteen").item != "fifteen") {
        return "fail 16: companion nested generic"
    }
    if (CompanionDeclarationHost.Companion.Internal(19).item != 19) {
        return "fail 17: companion nested visibility"
    }
    if (CompanionDeclarationHost.Companion.NestedObject.initialized != 3) {
        return "fail 18: object in companion"
    }
    if (CompanionDeclarationHost.Companion.NestedObject.Leaf(21).item != 21) {
        return "fail 19: class in companion object child"
    }
    if (CompanionDeclarationHost.Companion.CompanionOwner.initialized != 4) {
        return "fail 20: companion below companion"
    }
    if (CompanionDeclarationHost.Companion.ForwardFactory().create(16).item != 16) {
        return "fail 21: companion forward reference"
    }

    if (GenericMetadataOwner.Singleton.Independent("generic").item != "generic") {
        return "fail 22: generic metadata independence"
    }
    if (GenericMetadataOwner.Singleton.NestedObject.initialized != 5) {
        return "fail 23: object below generic metadata ancestor"
    }
    if (GenericMetadataOwner.Singleton.NestedObject.Leaf(22).item != 22) {
        return "fail 24: class below generic metadata ancestor"
    }
    if (GenericMetadataOwner.Singleton.CompanionOwner.initialized != 6) {
        return "fail 25: companion below generic metadata ancestor"
    }
    if (nestedOwnerInitializationCount != 6) return "fail 26: singleton initialization count"
    return "OK"
}
