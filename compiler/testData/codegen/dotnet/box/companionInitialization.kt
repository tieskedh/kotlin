// LANGUAGE: +CompanionBlocks +CompanionExtensions

private var order = ""

private fun initialized(tag: String): String {
    order += tag
    return tag
}

open class StateParent {
    companion {
        val state = initialized("P")
    }
}

class StatelessChild : StateParent() {
    companion {
        fun touch(): String = "touch"
    }
}

open class Base {
    companion {
        val state = initialized("B")
    }
}

interface FirstDefault {
    companion {
        val state = initialized("F")
    }

    fun first(): String = "first"
}

interface SecondDefault {
    companion {
        val state = initialized("S")
    }

    fun second(): String = "second"
}

interface AbstractOnly {
    companion {
        val state = initialized("A")
    }

    fun abstractMember(): String
}

class OrderedChild : SecondDefault, Base(), AbstractOnly, FirstDefault {
    companion {
        val state = initialized("C")
    }

    override fun abstractMember(): String = "abstract"
}

class GenericState<T> {
    companion {
        val state = initialized("G")
    }
}

class VisibilityOwner {
    private open class PrivateBase {
        companion {
            val state = initialized("V")
        }
    }

    private class PrivateChild : PrivateBase() {
        companion {
            fun touch(): String = "touch"
        }
    }

    companion {
        fun trigger(): String = PrivateChild.touch()
    }
}

class MixedState {
    companion {
        val first = initialized("1")
    }

    companion object {
        val objectState = initialized("2")
    }

    companion {
        val last = initialized("3")
    }
}

class GenericObjectState<T> {
    companion object {
        val state = initialized("O")
    }
}

class GenericMixedState<T> {
    companion {
        val first = initialized("X")
    }

    companion object {
        val objectState = initialized("Y")
    }

    companion {
        val last = initialized("Z")
    }
}

interface MixedInterfaceState {
    companion {
        val first = initialized("I")
    }

    companion object {
        val objectState = initialized("J")
    }

    companion {
        val last = initialized("K")
    }
}

fun box(): String {
    if (StatelessChild.touch() != "touch") return "FAIL: stateless child call"
    if (order != "P") return "FAIL: stateless child order=$order"

    val child = OrderedChild()
    if (child.first() != "first" || child.second() != "second") return "FAIL: interface defaults"
    if (child.abstractMember() != "abstract") return "FAIL: abstract member"
    if (order != "PBSFC") return "FAIL: selected graph order=$order"

    GenericState<String>()
    GenericState<Int>()
    if (GenericState.state != "G") return "FAIL: generic state=${GenericState.state}"
    if (order != "PBSFCG") return "FAIL: generic initialization count=$order"

    if (VisibilityOwner.trigger() != "touch") return "FAIL: private child call"
    if (order != "PBSFCGV") return "FAIL: private inheritance=$order"

    if (MixedState.objectState != "2") return "FAIL: mixed object state"
    if (MixedState.first != "1" || MixedState.last != "3") return "FAIL: mixed block state"
    if (order != "PBSFCGV123") return "FAIL: mixed source order=$order"

    if (GenericMixedState.objectState != "Y") return "FAIL: generic mixed object state"
    if (GenericMixedState.first != "X" || GenericMixedState.last != "Z") return "FAIL: generic mixed block state"
    GenericMixedState<String>()
    GenericMixedState<Int>()
    if (order != "PBSFCGV123XYZ") return "FAIL: generic mixed source order=$order"

    if (MixedInterfaceState.objectState != "J") return "FAIL: interface mixed object state"
    if (MixedInterfaceState.first != "I" || MixedInterfaceState.last != "K") {
        return "FAIL: interface mixed block state"
    }
    if (order != "PBSFCGV123XYZIJK") return "FAIL: interface mixed source order=$order"

    GenericObjectState<String>()
    GenericObjectState<Int>()
    if (GenericObjectState.state != "O") return "FAIL: generic object state"
    if (order != "PBSFCGV123XYZIJKO") return "FAIL: generic object initialization=$order"

    if (AbstractOnly.state != "A") return "FAIL: abstract-only state=${AbstractOnly.state}"
    if (order != "PBSFCGV123XYZIJKOA") return "FAIL: abstract-only independence=$order"

    if (OrderedChild.state != "C" || StateParent.state != "P") return "FAIL: repeated access"
    if (order != "PBSFCGV123XYZIJKOA") return "FAIL: repeated initialization=$order"
    return "OK"
}
