// LANGUAGE: +CompanionBlocks +CompanionExtensions

// This historical rejection file now pins the field-backed holder, inherited initialization, and
// mixed companion-block/companion-object shapes which have moved into the supported surface.

open class Parent {
    companion {
        val parentState = 1
    }
}

class Inherited : Parent() {
    companion {
        fun inheritedValue(): Int = 2
    }
}

class GenericState<T> {
    companion {
        val genericState = 3
    }
}

class MixedCompanions {
    companion object {
        val objectState = 3
    }

    companion {
        val blockState = 4
    }
}

interface InterfaceState {
    companion {
        val interfaceState = 5
    }
}

fun main() {
    println(Parent.parentState)
}
