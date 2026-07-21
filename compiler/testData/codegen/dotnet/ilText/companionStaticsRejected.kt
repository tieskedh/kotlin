// LANGUAGE: +CompanionBlocks +CompanionExtensions

// These declarations stay rejected until initialization-graph lowering assigns explicit
// cross-owner initialization edges. Stateless generic/interface members are covered separately.

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
