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

    if (AbstractOnly.state != "A") return "FAIL: abstract-only state=${AbstractOnly.state}"
    if (order != "PBSFCGVA") return "FAIL: abstract-only independence=$order"

    if (OrderedChild.state != "C" || StateParent.state != "P") return "FAIL: repeated access"
    if (order != "PBSFCGVA") return "FAIL: repeated initialization=$order"
    return "OK"
}
