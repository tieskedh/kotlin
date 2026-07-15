private var trace: String = ""
private var sizeCalls: Int = 0
private var initializerFactories: Int = 0

private fun countedSize(value: Int): Int {
    sizeCalls = sizeCalls + 1
    trace = trace + "size;"
    return value
}

private fun recorded(index: Int): Int {
    trace = trace + "i" + index + ";"
    return index * 2
}

private fun initializerFactory(offset: Int): (Int) -> Int {
    initializerFactories = initializerFactories + 1
    return { index -> offset + index }
}

private fun orderedInitializerFactory(): (Int) -> Int {
    trace = trace + "factory;"
    return { index ->
        trace = trace + "invoke;"
        index
    }
}

private class Item(val value: Int)

private class GenericItem<T>(val value: T)

private class Initializer(private val offset: Int) : (Int) -> Int {
    override fun invoke(index: Int): Int = offset + index
}

private fun throughCallable(size: Int, initializer: (Int) -> Int): IntArray =
    IntArray(size, initializer)

private fun directReference(index: Int): Int = index + 20

private fun primitiveInitializers(): String {
    val longs = LongArray(2) { 5L }
    val doubles = DoubleArray(2) { index -> if (index == 0) 1.25 else 2.75 }
    val booleans = BooleanArray(2) { index -> index == 0 }
    val chars = CharArray(2) { index -> if (index == 0) 'A' else 'B' }
    return "${longs[1]}:${doubles[0] + doubles[1]}:${booleans[0]}:${booleans[1]}:${chars[0].code + chars[1].code}"
}

private fun negativeLength(): String {
    var initialized = false
    return try {
        IntArray(-1) {
            initialized = true
            it
        }
        "not-thrown"
    } catch (_: ArithmeticException) {
        "wrong-arithmetic"
    } catch (_: IllegalArgumentException) {
        "wrong-argument"
    } catch (_: Exception) {
        if (initialized) "initialized" else "caught"
    }
}

private fun negativeLengthEvaluationOrder(): String {
    trace = ""
    return try {
        IntArray(countedSize(-1), orderedInitializerFactory())
        "not-thrown"
    } catch (_: Exception) {
        trace
    }
}

private fun nothingInitializerEvaluationOrder(): String {
    trace = ""
    return try {
        IntArray(countedSize(2), throw Exception())
        "not-thrown"
    } catch (_: Exception) {
        trace
    }
}

private fun initializerExceptionOrder(): String {
    trace = ""
    return try {
        IntArray(4) { index ->
            trace = trace + index + ";"
            if (index == 2) throw Exception()
            index
        }
        "not-thrown"
    } catch (_: Exception) {
        trace
    }
}

fun box(): String {
    trace = ""
    sizeCalls = 0
    val ordered = IntArray(countedSize(3), ::recorded)
    if (sizeCalls != 1) return "fail 1: size evaluated $sizeCalls times"
    if (trace != "size;i0;i1;i2;") return "fail 2: order $trace"
    if (ordered[0] != 0 || ordered[1] != 2 || ordered[2] != 4) return "fail 3: Int values"

    if (primitiveInitializers() != "5:4.0:true:false:131") return "fail 4: primitive families"

    val strings = Array(3) { index -> if (index == 1) "middle" else "edge" }
    if (strings[0] != "edge" || strings[1] != "middle" || strings[2] != "edge") {
        return "fail 5: strings"
    }
    val nullable = Array<String?>(2) { index -> if (index == 0) null else "value" }
    if (nullable[0] != null || nullable[1] != "value") return "fail 6: nullable references"
    val items = Array(2) { index -> Item(index + 3) }
    if (items[0].value != 3 || items[1].value != 4) return "fail 7: user classes"
    val genericItems = Array(2) { index -> GenericItem("v$index") }
    if (genericItems[0].value != "v0" || genericItems[1].value != "v1") return "fail 8: generic classes"

    var offset = 10
    val captured: (Int) -> Int = { index -> offset + index }
    val capturedValues = throughCallable(2, captured)
    offset = 100
    if (capturedValues[0] != 10 || capturedValues[1] != 11) return "fail 9: captured callable"

    initializerFactories = 0
    val factoryValues = throughCallable(2, initializerFactory(30))
    if (initializerFactories != 1 || factoryValues[0] != 30 || factoryValues[1] != 31) {
        return "fail 10: initializer expression"
    }
    val directFactoryValues = IntArray(2, initializerFactory(35))
    if (initializerFactories != 2 || directFactoryValues[0] != 35 || directFactoryValues[1] != 36) {
        return "fail 10a: direct initializer expression"
    }

    val implemented = IntArray(2, Initializer(40))
    if (implemented[0] != 40 || implemented[1] != 41) return "fail 11: implemented Function1"
    val referenced = IntArray(2, ::directReference)
    if (referenced[0] != 20 || referenced[1] != 21) return "fail 12: function reference"

    var zeroCalls = 0
    val empty = IntArray(0) {
        zeroCalls = zeroCalls + 1
        it
    }
    if (empty.size != 0 || zeroCalls != 0) return "fail 13: zero size"
    val early = IntArray(3) initializer@{ index ->
        if (index == 1) return@initializer 9
        index
    }
    if (early[0] != 0 || early[1] != 9 || early[2] != 2) return "fail 14: local return"
    if (negativeLength() != "caught") return "fail 15: negative ${negativeLength()}"
    if (negativeLengthEvaluationOrder() != "size;factory;") {
        return "fail 16: negative evaluation order $trace"
    }
    if (nothingInitializerEvaluationOrder() != "size;") {
        return "fail 17: Nothing evaluation order $trace"
    }
    if (initializerExceptionOrder() != "0;1;2;") return "fail 18: exception order $trace"

    fun local(index: Int): Int = index + 50
    val localValues = IntArray(2, ::local)
    if (localValues[0] != 50 || localValues[1] != 51) return "fail 19: local reference"
    return "OK"
}
