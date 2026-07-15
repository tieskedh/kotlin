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

fun main() {
    println(nestedOwnerInitializationCount)
    println(ObjectDeclarationHost.Reader().read())
    println(ObjectDeclarationHost.hiddenValue())
    val objectContract: NestedOwnerContract = ObjectDeclarationHost.Derived(8)
    println(objectContract.value())
    println(ObjectDeclarationDerived(9).value())
    println(ObjectDeclarationHost.Independent("ten").item)
    println(ObjectDeclarationHost.Internal(18).item)
    println(ObjectDeclarationHost.NestedObject.initialized)
    println(ObjectDeclarationHost.NestedObject.Leaf(20).item)
    println(ObjectDeclarationHost.CompanionOwner.initialized)
    println(ObjectDeclarationHost.ForwardFactory().create(10).item)
    println(CompanionDeclarationHost.Companion.Reader().read())
    println(CompanionDeclarationHost.hiddenValue())
    val companionContract: NestedOwnerContract = CompanionDeclarationHost.Companion.Derived(13)
    println(companionContract.value())
    println(CompanionDeclarationDerived(14).value())
    println(CompanionDeclarationHost.Companion.Independent("fifteen").item)
    println(CompanionDeclarationHost.Companion.Internal(19).item)
    println(CompanionDeclarationHost.Companion.NestedObject.initialized)
    println(CompanionDeclarationHost.Companion.NestedObject.Leaf(21).item)
    println(CompanionDeclarationHost.Companion.CompanionOwner.initialized)
    println(CompanionDeclarationHost.Companion.ForwardFactory().create(16).item)
    println(GenericMetadataOwner.Singleton.Independent("generic").item)
    println(GenericMetadataOwner.Singleton.NestedObject.initialized)
    println(GenericMetadataOwner.Singleton.NestedObject.Leaf(22).item)
    println(GenericMetadataOwner.Singleton.CompanionOwner.initialized)
    println(nestedOwnerInitializationCount)
}
