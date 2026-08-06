// Reified call sites must become ordinary concrete CLR operations before codegen. The embedded
// KLIB body, not a physical CLR generic dispatch route, is authoritative for Kotlin consumers.

import kotlin.enums.enumEntries

private class ReifiedBox<T>(val value: T)
private interface ReifiedMarker<T>
private class ReifiedMarkerImpl : ReifiedMarker<String>

private enum class ReifiedState { READY, BUSY }
private enum class ReifiedSingleState { ONLY }
private enum class ReifiedEmptyState {}

private inline fun <reified T> reifiedIs(value: Any?): Boolean = value is T
private inline fun <reified T> reifiedCast(value: Any?): T = value as T
private inline fun <reified T> reifiedSafeCast(value: Any?): T? = value as? T
private inline fun <reified T : Any> reifiedClass() = T::class
private inline fun <reified T> reifiedNested(value: Any?): Boolean = reifiedIs<T>(value)
private inline fun <reified T> reifiedArray(value: T): Array<T> = arrayOf(value)
private inline fun <reified T> reifiedNullArray(size: Int): Array<T?> = arrayOfNulls<T>(size)

fun box(): String {
    if (!reifiedIs<String>("ok") || reifiedIs<String>(1)) return "fail 1"
    if (!reifiedIs<Int>(1) || reifiedIs<Int>("1")) return "fail 2"
    if (!reifiedIs<String?>(null) || reifiedIs<String>(null)) return "fail 3"
    if (!reifiedIs<Int?>(null) || !reifiedIs<Int?>(1)) return "fail 3b"
    if (!reifiedIs<Nothing?>(null) || reifiedIs<Nothing?>("value")) return "fail 3c"
    if (!reifiedIs<Throwable>(Error("classified"))) return "fail 3d"
    if (!reifiedIs<IntArray>(intArrayOf(1)) || !reifiedIs<Array<*>>(arrayOf("a"))) return "fail 3e"
    if (reifiedCast<String>("cast") != "cast") return "fail 4"
    if (reifiedCast<Array<String>>(arrayOf("cast"))[0] != "cast") return "fail 4b"
    if (reifiedSafeCast<String>(1) != null || reifiedSafeCast<Int>(1) != 1) return "fail 5"
    try {
        reifiedCast<Int>("wrong")
        return "fail 5b"
    } catch (_: ClassCastException) {
    }
    if (reifiedClass<String>() != String::class || reifiedClass<Int>() != Int::class) return "fail 6"
    if (!reifiedNested<CharSequence>("nested")) return "fail 7"

    val erased: Any = ReifiedBox("text")
    if (!reifiedIs<ReifiedBox<Int>>(erased)) return "fail 8"
    val wrong = reifiedCast<ReifiedBox<Int>>(erased)
    if (wrong as Any !== erased) return "fail 9"
    val marker: Any = ReifiedMarkerImpl()
    if (!reifiedIs<ReifiedMarker<Int>>(marker)) return "fail 9b"

    val values = reifiedArray(42)
    if (values.size != 1 || values[0] != 42) return "fail 10"
    val nulls = reifiedNullArray<String>(2)
    if (nulls.size != 2 || nulls[0] != null || nulls[1] != null) return "fail 11"

    val mixed: Array<Any?> = arrayOf("a", 1, null, "b")
    val strings = mixed.filterIsInstance<String>()
    if (strings.size != 2 || strings[0] != "a" || strings[1] != "b") return "fail 12"
    val ints = mixed.asIterable().filterIsInstanceTo(ArrayList<Int>())
    if (ints.size != 1 || ints[0] != 1) return "fail 13"
    val typed = arrayOf("x", "y").asList().toTypedArray()
    if (typed.size != 2 || typed[0] != "x" || typed[1] != "y") return "fail 14"
    val absent: Array<String>? = null
    if (absent.orEmpty().size != 0) return "fail 15"

    val stateValues = enumValues<ReifiedState>()
    if (stateValues.size != 2 || stateValues[0] !== ReifiedState.READY || stateValues[1] !== ReifiedState.BUSY) {
        return "fail 16"
    }
    if (stateValues === enumValues<ReifiedState>()) return "fail 16b"
    val singleValues = enumValues<ReifiedSingleState>()
    if (singleValues.size != 1 || singleValues[0] !== ReifiedSingleState.ONLY) return "fail 16c"
    if (enumValues<ReifiedEmptyState>().size != 0) return "fail 16d"
    if (enumValueOf<ReifiedState>("BUSY") !== ReifiedState.BUSY) return "fail 17"
    try {
        enumValueOf<ReifiedState>("MISSING")
        return "fail 17b"
    } catch (_: IllegalArgumentException) {
    }
    val entries = enumEntries<ReifiedState>()
    if (entries.size != 2 || entries[0] !== ReifiedState.READY || entries[1] !== ReifiedState.BUSY) {
        return "fail 18"
    }
    if (entries !== enumEntries<ReifiedState>()) return "fail 18b"

    return "OK"
}
