// LANGUAGE: +CompanionBlocks +CompanionExtensions

var initialized = false

private fun initializeState(): Int {
    initialized = true
    return 40
}

class CompanionOwner {
    companion {
        var state = initializeState()

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

fun box(): String {
    if (CompanionOwner.detachedFunction(4) != 5) return "FAIL: detached function"
    if (CompanionOwner.detachedProperty != 7) return "FAIL: detached property"
    if (initialized) return "FAIL: companion extension initialized its target"

    if (CompanionOwner.answer != 42) return "FAIL: answer=${CompanionOwner.answer}"
    if (!initialized) return "FAIL: companion block did not initialize"
    if (CompanionOwner.add(2) != 42) return "FAIL: add=${CompanionOwner.state}"
    return "OK"
}
