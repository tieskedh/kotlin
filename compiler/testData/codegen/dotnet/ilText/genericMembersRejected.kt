// Ordinary non-reified inline methods and method parameters bounded by Kotlin-owned erased
// interfaces are the supported controls. Reified members remain unsupported and are omitted.

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
