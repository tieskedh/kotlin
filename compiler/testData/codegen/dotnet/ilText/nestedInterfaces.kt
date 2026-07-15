var nestedInterfaceInitializationCount: Int = 0

fun nextNestedInterfaceInitialization(): Int {
    nestedInterfaceInitializationCount += 1
    return nestedInterfaceInitializationCount
}

interface RootNestedContract {
    fun value(): Int
}

class ClassInterfaceHost {
    interface NestedContract : RootNestedContract

    class Implementation(private val stored: Int) : NestedContract {
        override fun value(): Int = stored
    }

    private interface PrivateContract
    internal interface InternalContract
    protected interface ProtectedContract
    interface PublicContract
}

object ObjectInterfaceHost {
    interface NestedContract {
        fun text(): String
    }
}

class ObjectInterfaceImplementation : ObjectInterfaceHost.NestedContract {
    override fun text(): String = "object"
}

class CompanionInterfaceHost {
    companion object {
        interface NestedContract
    }
}

interface InterfaceDeclarationHost {
    class Implementation(private val stored: Int) : LaterContract {
        override fun later(): Int = stored
    }

    interface LaterContract {
        fun later(): Int
    }

    interface Producer<out T> {
        fun produce(): T
    }

    class ProducerImplementation<T>(private val stored: T) : Producer<T> {
        override fun produce(): T = stored
    }

    object Singleton {
        val initialized: Int = nextNestedInterfaceInitialization()
    }

    private class Hidden

    companion object {
        val initialized: Int = nextNestedInterfaceInitialization()

        fun create(value: Int): LaterContract = Implementation(value)
    }
}

interface GenericInterfaceDeclarationHost<T> {
    interface Independent<out U> {
        fun item(): U
    }

    class IndependentImplementation<U>(private val stored: U) : Independent<U> {
        override fun item(): U = stored
    }

    object Singleton {
        val initialized: Int = nextNestedInterfaceInitialization()
    }
}

interface DeepInterfaceHost {
    interface Middle {
        interface Leaf {
            fun leaf(): Int
        }
    }
}

class DeepInterfaceImplementation : DeepInterfaceHost.Middle.Leaf {
    override fun leaf(): Int = 5
}

fun main() {
    println(nestedInterfaceInitializationCount)
    val rootContract: RootNestedContract = ClassInterfaceHost.Implementation(1)
    println(rootContract.value())
    println(InterfaceDeclarationHost.create(2).later())
    println(InterfaceDeclarationHost.initialized)
    val producer: InterfaceDeclarationHost.Producer<String> =
        InterfaceDeclarationHost.ProducerImplementation("three")
    println(producer.produce())
    println(InterfaceDeclarationHost.Singleton.initialized)
    val independent: GenericInterfaceDeclarationHost.Independent<Int> =
        GenericInterfaceDeclarationHost.IndependentImplementation(4)
    println(independent.item())
    println(GenericInterfaceDeclarationHost.Singleton.initialized)
    val leaf: DeepInterfaceHost.Middle.Leaf = DeepInterfaceImplementation()
    println(leaf.leaf())
    val objectContract: ObjectInterfaceHost.NestedContract = ObjectInterfaceImplementation()
    println(objectContract.text())
    println(nestedInterfaceInitializationCount)
}
