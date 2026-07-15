// TARGET_BACKEND: DOTNET

class ClassDataOwner {
    data class Entry(val value: Int)
}

class GenericDataOwner<T> {
    data class Entry(val value: Int)
}

interface InterfaceDataOwner<T> {
    data class Entry(val value: Int)
}

object ObjectDataOwner {
    data class Entry(val value: Int)
}

class CompanionDataOwner {
    companion object {
        data class Entry(val value: Int)
    }
}

class DeepDataOwner {
    class Middle {
        data class Entry(val value: Int)
    }
}

data class DataClassOwner(val owner: Int) {
    data class Entry(val value: Int)
}

fun box(): String {
    val classEntry = ClassDataOwner.Entry(1)
    if (classEntry != ClassDataOwner.Entry(1)) return "fail 1: class owner"
    val classCopy = classEntry.copy()
    if (classCopy === classEntry || classCopy != classEntry) return "fail 2: copy"

    val genericEntry = GenericDataOwner.Entry(2)
    if (genericEntry.toString() != "Entry(value=2)") return "fail 3: generic owner"
    if (genericEntry.component1() != 2) return "fail 4: component"

    if (InterfaceDataOwner.Entry(3) != InterfaceDataOwner.Entry(3)) return "fail 5: interface owner"
    if (ObjectDataOwner.Entry(4) != ObjectDataOwner.Entry(4)) return "fail 6: object owner"
    if (CompanionDataOwner.Companion.Entry(5) != CompanionDataOwner.Companion.Entry(5)) {
        return "fail 7: companion owner"
    }
    if (DeepDataOwner.Middle.Entry(6) != DeepDataOwner.Middle.Entry(6)) return "fail 8: deep owner"
    if (DataClassOwner.Entry(7) != DataClassOwner.Entry(7)) return "fail 9: data owner"

    val unrelated: Any? = GenericDataOwner.Entry(1)
    if (classEntry == unrelated) return "fail 10: nested class identity"
    return "OK"
}
