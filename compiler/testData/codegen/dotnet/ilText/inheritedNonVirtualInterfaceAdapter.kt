// Kotlin/JVM permits a final method inherited from a base class to satisfy an interface first
// declared by a derived class. The CLR interface slot cannot target that inherited non-virtual
// MethodDef directly, so the derived class needs one private virtual forwarding MethodImpl.
interface Able {
    fun f(): Int
}

open class Provider {
    fun f(): Int = 42
}

class Combined : Provider(), Able

fun throughInterface(value: Able): Int = value.f()

fun main() {
    println(throughInterface(Combined()))
}
