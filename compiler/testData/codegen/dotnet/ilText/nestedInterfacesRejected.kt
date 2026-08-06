// Nested interface defaults use the same profile-aware representation as top-level defaults:
// BrokenContract owns a portable <DefaultImpls> helper while its metadata parent and Good sibling
// survive. A valued annotation class inside an interface is an ordinary admitted nested declaration.
// A companion failure remains owner-sensitive. Generic interfaces store their companion instance
// on the canonical interface's non-generic holder; a companion on a non-generic interface may
// implement another supported interface and remains an ordinary nested singleton.

interface NestedInterfaceMarker

class NestedDefaultBodyHost {
    interface BrokenContract {
        fun broken(): Int = 1
    }

    class Good {
        fun value(): Int = 6
    }
}

interface NestedAnnotationDeclarationHost {
    annotation class NestedAnnotation(val value: Int)

    class Good {
        fun value(): Int = 7
    }
}

interface GenericCompanionInterface<T> {
    companion object
}

interface MarkedCompanionInterface {
    companion object : NestedInterfaceMarker
}

class UsesNestedInterfaceSurvivors {
    fun first(value: NestedDefaultBodyHost.Good): Int = value.value()

    fun second(value: NestedAnnotationDeclarationHost.Good): Int = value.value()
}

fun main() {
    val uses = UsesNestedInterfaceSurvivors()
    println(uses.first(NestedDefaultBodyHost.Good()) + uses.second(NestedAnnotationDeclarationHost.Good()))
}
