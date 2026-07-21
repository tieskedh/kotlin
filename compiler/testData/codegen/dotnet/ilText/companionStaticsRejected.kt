// LANGUAGE: +CompanionBlocks +CompanionExtensions

// These declarations stay rejected until the companion-holder and initialization-graph
// lowerings assign one non-generic physical owner and explicit cross-owner initialization edges.

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

class GenericOwner<T> {
    companion {
        fun create(): GenericOwner<String> = GenericOwner()
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

interface InterfaceOwner {
    companion {
        fun interfaceValue(): Int = 5
    }
}

fun main() {
    println(Parent.parentState)
}
