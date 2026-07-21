// LANGUAGE: +CompanionBlocks +CompanionExtensions

// This file pins the field-backed holder and inherited initialization shapes which used to be
// rejected. Mixed companion-block and companion-object state remains rejected until their source
// initializer streams can be merged without changing Kotlin ordering.

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
