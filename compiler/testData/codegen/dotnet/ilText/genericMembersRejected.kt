// Ordinary non-reified inline methods are the supported control. The remaining unsupported
// method-generic shapes reject their whole owning class or interface; they are never erased or
// emitted with weakened constraints.

class InlineMember {
    inline fun <T> id(value: T): T = value
}

class ReifiedMember {
    inline fun <reified T> id(value: T): T = value
}

class GenericBoundMember {
    fun <T : List<String>> use(value: T): T = value
}

interface GenericBoundMemberSlot {
    fun <T : List<String>> use(value: T): T
}

class Survivor(val value: Int)

fun main() {
    println(Survivor(17).value)
}
