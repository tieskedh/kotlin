// Ordinary and reified inline methods plus method parameters bounded by Kotlin-owned erased
// interfaces are the supported controls. A reified member has only an assembly-visible throwing
// physical remainder; Kotlin calls consume its KLIB body instead of dispatching to that MethodDef.

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
