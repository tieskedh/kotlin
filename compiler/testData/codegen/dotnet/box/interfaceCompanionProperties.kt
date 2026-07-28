// LANGUAGE: +CompanionBlocks +CompanionExtensions

private interface InterfaceCompanionState {
    companion {
        private var hidden = 40
        private val increment = 2

        val answer: Int
            get() = hidden + increment

        var mutable = 1

        var restricted = 2
            private set

        var exposedHidden: Int
            get() = hidden
            set(value) {
                hidden = value
            }
    }
}

fun box(): String {
    if (InterfaceCompanionState.answer != 42) return "FAIL: answer=${InterfaceCompanionState.answer}"

    InterfaceCompanionState.mutable = 7
    if (InterfaceCompanionState.mutable != 7) return "FAIL: mutable=${InterfaceCompanionState.mutable}"

    if (InterfaceCompanionState.restricted != 2) {
        return "FAIL: restricted=${InterfaceCompanionState.restricted}"
    }

    InterfaceCompanionState.exposedHidden = 10
    if (InterfaceCompanionState.exposedHidden != 10) {
        return "FAIL: hidden=${InterfaceCompanionState.exposedHidden}"
    }
    if (InterfaceCompanionState.answer != 12) return "FAIL: updated answer=${InterfaceCompanionState.answer}"
    return "OK"
}
