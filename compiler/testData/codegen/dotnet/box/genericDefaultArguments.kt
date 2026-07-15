// TARGET_BACKEND: DOTNET

private var defaultCalls: Int = 0

private fun <T> observe(value: T): T {
    defaultCalls = defaultCalls + 1
    return value
}

private fun <T> topDefault(value: T, selected: T = observe(value)): T = selected

private class GenericDefaults<T>(val value: T, val selected: T = observe(value)) {
    fun member(selected: T = observe(value)): T = selected

    fun throughMember(): T = member()

    fun <R> method(value: R, selected: R = observe(value)): R = selected
}

private data class GenericDefaultData<T>(val value: T)

private fun <T> verifyOpenDefaults(value: T, label: String): String? {
    defaultCalls = 0
    if (topDefault(value) != value || defaultCalls != 1) return "$label top"

    val owner = GenericDefaults(value)
    if (owner.selected != value || defaultCalls != 2) return "$label constructor"
    if (owner.member() != value || defaultCalls != 3) return "$label member"
    if (owner.throughMember() != value || defaultCalls != 4) return "$label class type parameter"
    if (owner.method(value) != value || defaultCalls != 5) return "$label method"

    val data = GenericDefaultData(value)
    if (data.copy() != data) return "$label data copy"
    return null
}

private fun verifyConcreteDefaults(): String? {
    defaultCalls = 0
    if (!topDefault(true)) return "boolean"
    if (topDefault('k') != 'k') return "char"
    if (topDefault(11) != 11) return "int"
    if (topDefault(12L) != 12L) return "long"
    if (topDefault(1.5) != 1.5) return "double"
    if (topDefault("direct") != "direct") return "reference"
    if (topDefault<Int?>(null) != null) return "nullable value"
    if (defaultCalls != 7) return "evaluation count $defaultCalls"
    return null
}

fun box(): String {
    val intResult = verifyOpenDefaults(7, "int")
    if (intResult != null) return "fail 1: $intResult"
    val stringResult = verifyOpenDefaults("value", "string")
    if (stringResult != null) return "fail 2: $stringResult"
    val nullResult = verifyOpenDefaults<String?>(null, "null")
    if (nullResult != null) return "fail 3: $nullResult"
    val nullableIntResult = verifyOpenDefaults<Int?>(null, "nullable int")
    if (nullableIntResult != null) return "fail 4: $nullableIntResult"
    val nullableIntValueResult = verifyOpenDefaults<Int?>(23, "nullable int value")
    if (nullableIntValueResult != null) return "fail 5: $nullableIntValueResult"
    val arrayResult = verifyOpenDefaults(intArrayOf(1, 2), "array")
    if (arrayResult != null) return "fail 6: $arrayResult"
    val concreteResult = verifyConcreteDefaults()
    if (concreteResult != null) return "fail 7: concrete $concreteResult"

    defaultCalls = 0
    val owner = GenericDefaults(1, 2)
    if (topDefault(1, 2) != 2 || owner.member(3) != 3 || owner.method("a", "b") != "b") {
        return "fail 8: explicit values"
    }
    if (defaultCalls != 0) return "fail 9: explicit evaluation $defaultCalls"
    return "OK"
}
