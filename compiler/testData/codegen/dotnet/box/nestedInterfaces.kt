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

fun box(): String {
    if (nestedInterfaceInitializationCount != 0) return "fail 1: eager singleton initialization"
    val rootContract: RootNestedContract = ClassInterfaceHost.Implementation(1)
    if (rootContract.value() != 1) return "fail 2: interface in class"
    if (InterfaceDeclarationHost.create(2).later() != 2) return "fail 3: forward declaration in interface"
    if (InterfaceDeclarationHost.initialized != 1) return "fail 4: interface companion initialization"
    val producer: InterfaceDeclarationHost.Producer<String> =
        InterfaceDeclarationHost.ProducerImplementation("three")
    if (producer.produce() != "three") {
        return "fail 5: generic declaration in interface"
    }
    if (InterfaceDeclarationHost.Singleton.initialized != 2) return "fail 6: object in interface"
    val independent: GenericInterfaceDeclarationHost.Independent<Int> =
        GenericInterfaceDeclarationHost.IndependentImplementation(4)
    if (independent.item() != 4) {
        return "fail 7: generic independence"
    }
    if (GenericInterfaceDeclarationHost.Singleton.initialized != 3) {
        return "fail 8: object in generic interface"
    }
    val leaf: DeepInterfaceHost.Middle.Leaf = DeepInterfaceImplementation()
    if (leaf.leaf() != 5) return "fail 9: recursive nested interface"
    val objectContract: ObjectInterfaceHost.NestedContract = ObjectInterfaceImplementation()
    if (objectContract.text() != "object") return "fail 10: interface in object"
    if (nestedInterfaceInitializationCount != 3) return "fail 11: repeated singleton initialization"
    return "OK"
}
