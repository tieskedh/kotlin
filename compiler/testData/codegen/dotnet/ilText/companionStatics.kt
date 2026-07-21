// LANGUAGE: +CompanionBlocks +CompanionExtensions

class CompanionOwner {
    companion {
        var state = 40

        val answer: Int
            get() = state + 2

        fun add(delta: Int): Int {
            state = state + delta
            return state
        }
    }
}

companion fun CompanionOwner.detachedFunction(value: Int): Int = value + 1
companion val CompanionOwner.detachedProperty: Int
    get() = 7

fun main() {
    println(CompanionOwner.answer)
    println(CompanionOwner.add(2))
    println(CompanionOwner.detachedFunction(4))
    println(CompanionOwner.detachedProperty)
}
