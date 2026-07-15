// TARGET_BACKEND: DOTNET

private interface LocalDataValue {
    fun value(): Int
}

private fun basicLocalDataClass(): String? {
    data class Local(val value: Int, val label: String?)

    val first = Local(7, null)
    val equal = Local(7, null)
    if (first === equal || first != equal) return "basic equality"
    if (first.hashCode() != 217) return "basic hash ${first.hashCode()}"
    if (first.toString() != "Local(value=7, label=null)") return "basic text $first"

    val (value, label) = first
    if (value != 7 || label != null) return "basic components"
    if (first.copy(value = 8) != Local(8, null)) return "basic copy"
    return null
}

private fun parameterCapture(seed: Int): String? {
    data class Local(val item: Int) : LocalDataValue {
        override fun value(): Int = seed + item
    }

    val original = Local(2)
    val copied = original.copy(item = 3)
    if (original.value() != seed + 2) return "parameter capture original"
    if (copied.value() != seed + 3) return "parameter capture copy"
    return null
}

private class LocalDataOwner(private val seed: Int) {
    fun check(): String? {
        data class Local(val item: Int) {
            fun value(): Int = this@LocalDataOwner.seed + item
        }

        if (Local(2).copy(item = 4).value() != seed + 4) return "outer-this capture"
        return null
    }
}

private fun mutableCapture(): String? {
    var state = 1
    data class Local(val amount: Int) {
        fun add(): Int {
            state = state + amount
            return state
        }
    }

    val original = Local(2)
    val copied = original.copy(amount = 3)
    if (original.add() != 3 || copied.add() != 6) return "mutable capture"
    return null
}

private fun localFunctionCapture(seed: Int): String? {
    fun compute(value: Int): Int = seed + value

    data class Local(val item: Int) {
        fun value(): Int = compute(item)
    }

    if (Local(2).copy(item = 5).value() != seed + 5) return "local-function capture"
    return null
}

private var defaultEvaluations: Int = 0

private fun nextDefault(): Int {
    defaultEvaluations = defaultEvaluations + 1
    return defaultEvaluations
}

private fun capturedDefaults(seed: Int): String? {
    defaultEvaluations = 0
    data class Local(val value: Int = nextDefault(), val label: String = "default") {
        fun captured(): Int = seed
    }

    val original = Local(label = "original")
    if (original.value != 1 || original.label != "original" || original.captured() != seed) {
        return "constructor defaults $original"
    }
    val copied = original.copy(label = "copy")
    if (copied.value != 1 || copied.label != "copy" || copied.captured() != seed) {
        return "copy defaults $copied"
    }
    if (defaultEvaluations != 1) return "default evaluation $defaultEvaluations"
    return null
}

private fun localArrayDataClass(): String? {
    data class Local(val values: IntArray)

    val values = intArrayOf(1, 2, 3)
    val first = Local(values)
    if (first != Local(values) || first == Local(intArrayOf(1, 2, 3))) return "array identity"
    if (first.hashCode() != 30817) return "array hash ${first.hashCode()}"
    if (first.toString() != "Local(values=[1, 2, 3])") return "array text $first"
    return null
}

private fun firstSameName(value: Int): Any {
    data class Same(val value: Int)
    return Same(value)
}

private fun secondSameName(value: Int): Any {
    data class Same(val value: Int)
    return Same(value)
}

private fun capturedIdentity(seed: Int): Any {
    data class Captured(val value: Int) {
        fun captured(): Int = seed
    }

    return Captured(7)
}

private fun localIdentity(): String? {
    if (!firstSameName(1).equals(firstSameName(1))) return "same declaration identity"
    if (firstSameName(1).equals(secondSameName(1))) return "different declaration identity"
    val firstCapture = capturedIdentity(10)
    val secondCapture = capturedIdentity(20)
    if (!firstCapture.equals(secondCapture)) return "capture leaked into equality"
    if (firstCapture.hashCode() != secondCapture.hashCode()) return "capture leaked into hash"
    if (firstCapture.toString() != secondCapture.toString()) return "capture leaked into text"
    return null
}

private fun <T> capturedGeneric(value: T): Any {
    data class Local(val value: T)
    return Local(value)
}

private fun <T> genericValueCapture(seed: Int, value: T): String? {
    data class Local(val value: T) {
        fun captured(): Int = seed
    }

    val copied = Local(value).copy(value = value)
    if (copied.captured() != seed) return "generic value capture"
    return null
}

private fun localGenericEquality(): String? {
    val capturedInt = capturedGeneric<Int>(1)
    val capturedAny = capturedGeneric<Any>(1)
    if (!capturedInt.equals(capturedAny) || !capturedAny.equals(capturedInt)) {
        return "captured generic equality"
    }

    data class Local<T>(val value: T)
    val ownInt: Any = Local(1)
    val ownAny: Any = Local<Any>(1)
    if (!ownInt.equals(ownAny) || !ownAny.equals(ownInt)) return "own generic equality"
    return genericValueCapture(41, "value")
}

fun box(): String {
    val basic = basicLocalDataClass()
    if (basic != null) return "fail 1: $basic"
    val parameter = parameterCapture(10)
    if (parameter != null) return "fail 2: $parameter"
    val outer = LocalDataOwner(20).check()
    if (outer != null) return "fail 3: $outer"
    val mutable = mutableCapture()
    if (mutable != null) return "fail 4: $mutable"
    val localFunction = localFunctionCapture(30)
    if (localFunction != null) return "fail 5: $localFunction"
    val defaults = capturedDefaults(40)
    if (defaults != null) return "fail 6: $defaults"
    val array = localArrayDataClass()
    if (array != null) return "fail 7: $array"
    val identity = localIdentity()
    if (identity != null) return "fail 8: $identity"
    val generic = localGenericEquality()
    if (generic != null) return "fail 9: $generic"
    return "OK"
}
