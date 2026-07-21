// Unsupported method-generic shapes reject their whole owning class or interface. They are never
// erased or emitted with weakened constraints.

class NullableMember {
    fun <T> use(value: T?): Int = 0
}

class InlineMember {
    inline fun <T> id(value: T): T = value
}

class ReifiedMember {
    inline fun <reified T> id(value: T): T = value
}

class GenericBoundMember {
    fun <T : List<String>> use(value: T): T = value
}

interface NullableMemberSlot {
    fun <T> use(value: T?): Int
}

interface GenericBoundMemberSlot {
    fun <T : List<String>> use(value: T): T
}

class Survivor(val value: Int)

fun main() {
    println(Survivor(17).value)
}
